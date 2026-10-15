/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.volcengine.veadk.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import com.volcengine.ark.runtime.exception.ArkAPIError;
import com.volcengine.ark.runtime.exception.ArkHttpException;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.responses.event.ErrorEvent;
import com.volcengine.ark.runtime.model.responses.event.StreamEvent;
import com.volcengine.ark.runtime.model.responses.event.outputtext.OutputTextDeltaEvent;
import com.volcengine.ark.runtime.model.responses.event.response.ResponseCompletedEvent;
import com.volcengine.ark.runtime.model.responses.request.CreateResponsesRequest;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.Runner;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ArkResponsesTest {
    private final ArkService service = mock(ArkService.class);
    private ArkLlm llm;

    @BeforeEach
    void setUp() throws Exception {
        llm = model(ArkLlmConfig.Api.RESPONSES, true);
    }

    @AfterEach
    void close() {
        llm.close();
    }

    private ArkLlm model(ArkLlmConfig.Api api, boolean store) throws Exception {
        var result =
                new ArkLlm(
                        ArkLlmConfig.builder()
                                .modelName("test-model")
                                .apiKey("test-key")
                                .api(api)
                                .responsesStore(store)
                                .thinking("disabled")
                                .build());
        Field field = ArkLlm.class.getDeclaredField("arkService");
        field.setAccessible(true);
        field.set(result, service);
        return result;
    }

    @Test
    void chatCompletionsRemainsTheDefault() {
        assertThat(ArkLlmConfig.builder().modelName("model").apiKey("test").build().getApi())
                .isEqualTo(ArkLlmConfig.Api.CHAT_COMPLETIONS);
    }

    @Test
    void responsesWireIncludesGenerationAndJsonSchemaInBothModes() throws Exception {
        when(service.createResponse(any())).thenReturn(raw("r1", "{\"ok\":true}"));
        when(service.streamResponse(any()))
                .thenReturn(io.reactivex.Flowable.just(done("r2", "{\"ok\":true}")));
        LlmRequest request = request("JSON please").toBuilder().config(structured()).build();
        llm.generateContent(request, false).blockingFirst();
        llm.generateContent(request, true).toList().blockingGet();
        var ordinary = ArgumentCaptor.forClass(CreateResponsesRequest.class);
        var streaming = ArgumentCaptor.forClass(CreateResponsesRequest.class);
        verify(service).createResponse(ordinary.capture());
        verify(service).streamResponse(streaming.capture());
        for (CreateResponsesRequest wire : List.of(ordinary.getValue(), streaming.getValue())) {
            JsonNode json = wire(wire);
            assertThat(
                            json.path("text")
                                    .path("format")
                                    .path("schema")
                                    .path("properties")
                                    .path("ok")
                                    .path("type")
                                    .asText())
                    .isEqualTo("boolean");
            assertThat(json.path("text").path("format").path("strict").asBoolean()).isTrue();
            assertThat(json.path("text").path("format").path("name").asText())
                    .isEqualTo("agent_response");
            assertThat(json.path("max_output_tokens").asInt()).isEqualTo(128);
            assertThat(json.path("temperature").asDouble()).isEqualTo(0.25);
            assertThat(json.path("top_p").asDouble()).isEqualTo(0.75);
            assertThat(json.path("input").get(0).path("type").asText()).isEqualTo("message");
            assertThat(json.toString()).doesNotContain(ArkResponsesSession.CONTEXT);
        }
    }

    @Test
    void chatSchemaUsesChatProtocolFields() throws Exception {
        try (ArkLlm chat = model(ArkLlmConfig.Api.CHAT_COMPLETIONS, false)) {
            when(service.createChatCompletion(any()))
                    .thenThrow(new IllegalStateException("capture only"));
            assertThatThrownBy(
                    () ->
                            chat.generateContent(
                                            request("JSON").toBuilder()
                                                    .config(structured())
                                                    .build(),
                                            false)
                                    .blockingFirst());
            var captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
            verify(service).createChatCompletion(captor.capture());
            JsonNode json = wire(captor.getValue());
            assertThat(json.path("response_format").path("type").asText()).isEqualTo("json_schema");
            assertThat(
                            json.path("response_format")
                                    .path("json_schema")
                                    .path("schema")
                                    .path("type")
                                    .asText())
                    .isEqualTo("object");
            assertThat(json.path("max_tokens").asInt()).isEqualTo(128);
        }
    }

    @Test
    void jsonMimeWithoutSchemaRequestsJsonObject() throws Exception {
        when(service.createResponse(any())).thenReturn(raw("r1", "{}"));
        llm.generateContent(
                        request("JSON").toBuilder()
                                .config(
                                        GenerateContentConfig.builder()
                                                .responseMimeType("application/json")
                                                .build())
                                .build(),
                        false)
                .blockingFirst();
        assertThat(captured(1).get(0).getText().getFormat().getType()).isEqualTo("json_object");
    }

    @Test
    void responsesPreservesMixedTextAndInlineImageParts() throws Exception {
        when(service.createResponse(any())).thenReturn(raw("r1", "seen"));
        Content content =
                Content.builder()
                        .role("user")
                        .parts(
                                Part.fromText("What is shown?"),
                                Part.fromBytes(new byte[] {1, 2, 3}, "image/png"))
                        .build();

        llm.generateContent(LlmRequest.builder().contents(List.of(content)).build(), false)
                .blockingFirst();

        JsonNode parts = wire(captured(1).get(0)).path("input").get(0).path("content");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).path("type").asText()).isEqualTo("input_text");
        assertThat(parts.get(0).path("text").asText()).isEqualTo("What is shown?");
        assertThat(parts.get(1).path("type").asText()).isEqualTo("input_image");
        assertThat(parts.get(1).path("image_url").asText()).isEqualTo("data:image/png;base64,AQID");
        assertThat(parts.get(1).path("detail").asText()).isEqualTo("auto");
    }

    @Test
    void responsesMapsImageUrlVideoUrlAndUploadedFileId() throws Exception {
        when(service.createResponse(any())).thenReturn(raw("r1", "seen"));
        Content content =
                Content.builder()
                        .role("user")
                        .parts(
                                Part.fromUri("https://example.test/photo.webp", "image/webp"),
                                Part.fromUri("https://example.test/clip.mp4", "video/mp4"),
                                Part.fromUri("file_id://file-123", "application/pdf"))
                        .build();

        llm.generateContent(LlmRequest.builder().contents(List.of(content)).build(), false)
                .blockingFirst();

        JsonNode parts = wire(captured(1).get(0)).path("input").get(0).path("content");
        assertThat(parts.get(0).path("type").asText()).isEqualTo("input_image");
        assertThat(parts.get(0).path("image_url").asText())
                .isEqualTo("https://example.test/photo.webp");
        assertThat(parts.get(1).path("type").asText()).isEqualTo("input_video");
        assertThat(parts.get(1).path("video_url").asText())
                .isEqualTo("https://example.test/clip.mp4");
        assertThat(parts.get(2).path("type").asText()).isEqualTo("input_file");
        assertThat(parts.get(2).path("file_id").asText()).isEqualTo("file-123");
    }

    @Test
    void toolResponseKeepsCallIdAndUsageThenReusesOnlyToolResult() throws Exception {
        var rawTool = rawTool("r1");
        when(service.createResponse(any())).thenReturn(rawTool, raw("r2", "done"));
        LlmRequest first = withTool(request("lookup"));
        LlmResponse response = llm.generateContent(first, false).blockingFirst();
        assertThat(ArkResponsesSession.responseId(response)).contains("r1");
        assertThat(
                        response.content()
                                .orElseThrow()
                                .parts()
                                .orElseThrow()
                                .get(0)
                                .functionCall()
                                .orElseThrow()
                                .id())
                .contains("call-1");
        assertThat(response.usageMetadata().orElseThrow().totalTokenCount()).contains(12);
        Content result =
                Content.fromParts(
                        Part.builder()
                                .functionResponse(
                                        FunctionResponse.builder()
                                                .id("call-1")
                                                .name("lookup")
                                                .response(Map.of("value", 1))
                                                .build())
                                .build());
        LlmRequest second = continuation(first, response, result);
        llm.generateContent(second, false).blockingFirst();
        var sent = captured(2);
        assertThat(sent.get(1).getPreviousResponseId()).isEqualTo("r1");
        assertThat(sent.get(1).getTools()).isNull();
        JsonNode input = wire(sent.get(1)).path("input");
        assertThat(input.size()).isEqualTo(1);
        assertThat(input.get(0).path("type").asText()).isEqualTo("function_call_output");
        assertThat(input.get(0).path("call_id").asText()).isEqualTo("call-1");
    }

    @Test
    void expiredIdRetriesOnceWithCompleteHistoryInstructionsAndTools() throws Exception {
        when(service.createResponse(any()))
                .thenReturn(raw("r1", "first"))
                .thenThrow(expired())
                .thenReturn(raw("r2", "second"));
        LlmRequest first =
                withTool(request("hello")).toBuilder()
                        .config(
                                GenerateContentConfig.builder()
                                        .systemInstruction(
                                                Content.fromParts(Part.fromText("Be helpful")))
                                        .build())
                        .build();
        var response = llm.generateContent(first, false).blockingFirst();
        llm.generateContent(continuation(first, response, user("next")), false).blockingFirst();
        var sent = captured(3);
        assertThat(sent.get(1).getPreviousResponseId()).isEqualTo("r1");
        assertThat(sent.get(2).getPreviousResponseId()).isNull();
        assertThat(sent.get(2).getTools()).hasSize(1);
        assertThat(wire(sent.get(2)).path("input").size()).isEqualTo(4);
        assertThat(wire(sent.get(2)).path("input").get(0).path("role").asText())
                .isEqualTo("system");
    }

    @Test
    void expiryRetryIsBounded() throws Exception {
        when(service.createResponse(any())).thenReturn(raw("r1", "first")).thenThrow(expired());
        var first = request("hello");
        var response = llm.generateContent(first, false).blockingFirst();
        assertThatThrownBy(
                        () ->
                                llm.generateContent(
                                                continuation(first, response, user("next")), false)
                                        .blockingFirst())
                .isInstanceOf(ArkHttpException.class);
        captured(3);
    }

    @Test
    void modifiedHistoryOrConfigurationStartsFresh() throws Exception {
        when(service.createResponse(any())).thenReturn(raw("r1", "first"));
        var first = request("hello");
        var response = llm.generateContent(first, false).blockingFirst();
        var second = continuation(first, response, user("next"));
        List<Content> changed = new ArrayList<>(second.contents());
        changed.set(0, user("different session"));
        llm.generateContent(second.toBuilder().contents(changed).build(), false).blockingFirst();
        llm.generateContent(
                        second.toBuilder()
                                .config(
                                        second.config().orElseThrow().toBuilder()
                                                .temperature(0.1f)
                                                .build())
                                .build(),
                        false)
                .blockingFirst();
        assertThat(captured(3).subList(1, 3))
                .allSatisfy(wire -> assertThat(wire.getPreviousResponseId()).isNull());
    }

    @Test
    void storeDisabledOmitsContinuationButStillExposesResponseId() throws Exception {
        when(service.createResponse(any())).thenReturn(raw("r1", "first"));
        try (ArkLlm noStore = model(ArkLlmConfig.Api.RESPONSES, false)) {
            var first = request("hello");
            var response = noStore.generateContent(first, false).blockingFirst();
            assertThat(ArkResponsesSession.responseId(response)).contains("r1");
            assertThat(ArkResponsesSession.metadata(response, ArkResponsesSession.CONTEXT))
                    .isEmpty();
            noStore.generateContent(continuation(first, response, user("next")), false)
                    .blockingFirst();
            assertThat(captured(2))
                    .allSatisfy(
                            wire -> {
                                assertThat(wire.getStore()).isFalse();
                                assertThat(wire.getPreviousResponseId()).isNull();
                            });
        }
    }

    @Test
    void streamEmitsDeltasAndOneFinalResponseWithUsageAndId() throws Exception {
        when(service.streamResponse(any()))
                .thenReturn(
                        io.reactivex.Flowable.just(delta("he"), delta("llo"), done("r1", "hello")));
        var responses = llm.generateContent(request("hi"), true).toList().blockingGet();
        assertThat(responses).hasSize(3);
        assertThat(responses.subList(0, 2))
                .allSatisfy(
                        response -> {
                            assertThat(response.partial()).contains(true);
                            assertThat(response.usageMetadata()).isEmpty();
                        });
        assertThat(responses.get(2).partial()).contains(false);
        assertThat(responses.get(2).usageMetadata().orElseThrow().totalTokenCount()).contains(12);
        assertThat(ArkResponsesSession.responseId(responses.get(2))).contains("r1");
    }

    @Test
    void streamWithOnlyToolsStillProducesFinalResponse() throws Exception {
        var event = new ResponseCompletedEvent();
        event.setResponse(rawTool("r1"));
        when(service.streamResponse(any())).thenReturn(io.reactivex.Flowable.just(event));
        var response = llm.generateContent(request("lookup"), true).blockingFirst();
        assertThat(response.content().orElseThrow().parts().orElseThrow().get(0).functionCall())
                .isPresent();
    }

    @Test
    void expiredStreamBeforeOutputRetriesFullRequest() throws Exception {
        var first = request("hello");
        when(service.createResponse(any())).thenReturn(raw("r1", "first"));
        var response = llm.generateContent(first, false).blockingFirst();
        when(service.streamResponse(any()))
                .thenReturn(
                        io.reactivex.Flowable.just(expiredEvent()),
                        io.reactivex.Flowable.just(done("r2", "ok")));
        var results =
                llm.generateContent(continuation(first, response, user("next")), true)
                        .toList()
                        .blockingGet();
        assertThat(results).hasSize(1);
        var captor = ArgumentCaptor.forClass(CreateResponsesRequest.class);
        verify(service, times(2)).streamResponse(captor.capture());
        assertThat(captor.getAllValues().get(1).getPreviousResponseId()).isNull();
    }

    @Test
    void streamErrorAfterOutputCannotRetryOrEmitCompletion() throws Exception {
        var first = request("hello");
        when(service.createResponse(any())).thenReturn(raw("r1", "first"));
        var response = llm.generateContent(first, false).blockingFirst();
        when(service.streamResponse(any()))
                .thenReturn(io.reactivex.Flowable.just(delta("partial"), expiredEvent()));
        var subscriber =
                llm.generateContent(continuation(first, response, user("next")), true).test();
        subscriber.assertValueCount(1).assertError(RuntimeException.class).assertNotComplete();
        assertThat(subscriber.values().get(0).partial()).contains(true);
        verify(service).streamResponse(any());
    }

    @Test
    void truncatedStreamDoesNotEmitSuccessfulCompletion() {
        when(service.streamResponse(any()))
                .thenReturn(io.reactivex.Flowable.just(delta("unfinished")));
        llm.generateContent(request("hi"), true)
                .test()
                .assertValueCount(1)
                .assertError(IllegalStateException.class);
    }

    @Test
    void incompleteNonstreamResponseIsAnError() throws Exception {
        var response = raw("r1", "{\"unfinished\":");
        response.setStatus("incomplete");
        when(service.createResponse(any())).thenReturn(response);
        assertThatThrownBy(() -> llm.generateContent(request("JSON"), false).blockingFirst())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void conflictingSchemaOptionsFailBeforeNetwork() {
        var config =
                structured().toBuilder()
                        .responseSchema(
                                com.google.genai.types.Schema.builder().type("OBJECT").build())
                        .build();
        llm.generateContent(request("json").toBuilder().config(config).build(), false)
                .test()
                .assertError(IllegalArgumentException.class);
        verify(service, never()).createResponse(any());
    }

    @Test
    void runnerPersistsIdsAndIsolatesSessions() throws Exception {
        when(service.createResponse(any()))
                .thenReturn(raw("r1", "first"), raw("r2", "second"), raw("r3", "other"));
        var continuity = new ArkResponsesSession();
        var agent =
                Agent.builder()
                        .name("responses_agent")
                        .model(llm)
                        .beforeModelCallback(continuity.beforeModel())
                        .afterModelCallback(continuity.afterModel())
                        .build();
        var runner = new Runner(agent);
        assertThat(runner.run("user", "session-a", "hello")).isEqualTo("first");
        assertThat(runner.run("user", "session-a", "next")).isEqualTo("second");
        assertThat(runner.run("user", "session-b", "hello")).isEqualTo("other");
        var sent = captured(3);
        assertThat(sent.get(1).getPreviousResponseId()).isEqualTo("r1");
        assertThat(sent.get(2).getPreviousResponseId()).isNull();
        var session =
                runner.sessionService()
                        .getSession("responses_agent", "user", "session-a", Optional.empty())
                        .blockingGet();
        assertThat(session.state().get(ArkResponsesSession.CONTEXT + ":responses_agent").toString())
                .contains("r2");
        assertThat(session.events())
                .anySatisfy(
                        event ->
                                assertThat(event.actions().stateDelta())
                                        .containsKey(
                                                ArkResponsesSession.CONTEXT + ":responses_agent"));
    }

    @Test
    void runnerToolExecutionUsesSavedResponseIdWithinTheSameInvocation() throws Exception {
        when(service.createResponse(any())).thenReturn(rawTool("r1"), raw("r2", "done"));
        var session = new ArkResponsesSession();
        var agent =
                Agent.builder()
                        .name("tool_agent")
                        .model(llm)
                        .tools(FunctionTool.create(Lookup.class, "lookup"))
                        .beforeModelCallback(session.beforeModel())
                        .afterModelCallback(session.afterModel())
                        .build();
        assertThat(new Runner(agent).run("user", "session", "lookup alpha")).isEqualTo("done");
        var sent = captured(2);
        assertThat(sent.get(1).getPreviousResponseId()).isEqualTo("r1");
        assertThat(wire(sent.get(1)).path("input").size()).isEqualTo(1);
        assertThat(wire(sent.get(1)).path("input").get(0).path("call_id").asText())
                .isEqualTo("call-1");
    }

    @Test
    void schemaNormalizationPreservesLiteralValuesAndSupportsNullable() {
        var schema =
                Map.of(
                        "type",
                        "OBJECT",
                        "properties",
                        Map.of("data", Map.of("type", "STRING", "nullable", true)),
                        "default",
                        Map.of("type", "KEEP_CASE"));
        var config = GenerateContentConfig.builder().responseJsonSchema(schema).build();
        var mapped = ArkStructuredOutput.schema(config);
        assertThat(mapped.path("default").path("type").asText()).isEqualTo("KEEP_CASE");
        assertThat(
                        mapped.path("properties")
                                .path("data")
                                .path("anyOf")
                                .get(0)
                                .path("type")
                                .asText())
                .isEqualTo("string");
        assertThat(
                        mapped.path("properties")
                                .path("data")
                                .path("anyOf")
                                .get(1)
                                .path("type")
                                .asText())
                .isEqualTo("null");
        assertThat(schema.toString()).contains("STRING");
    }

    @Test
    void nullableEnumsAndUnionsRetainTheirNonNullConstraints() {
        var enumeration = Map.of("type", "STRING", "enum", List.of("A", "B"), "nullable", true);
        var union =
                Map.of(
                        "anyOf",
                        List.of(Map.of("type", "STRING"), Map.of("type", "INTEGER")),
                        "nullable",
                        true);
        for (var schema : List.of(enumeration, union)) {
            var mapped =
                    ArkStructuredOutput.schema(
                            GenerateContentConfig.builder().responseJsonSchema(schema).build());
            assertThat(mapped.has("enum")).isFalse();
            assertThat(mapped.path("anyOf").get(1).path("type").asText()).isEqualTo("null");
            var nonNull = mapped.path("anyOf").get(0);
            if (schema.containsKey("enum"))
                assertThat(nonNull.path("enum").toString()).isEqualTo("[\"A\",\"B\"]");
            else assertThat(nonNull.path("anyOf").size()).isEqualTo(2);
        }
    }

    @Test
    void corruptContinuationStartsFromFullHistory() throws Exception {
        when(service.createResponse(any())).thenReturn(raw("r1", "ok"));
        var config =
                GenerateContentConfig.builder()
                        .httpOptions(
                                HttpOptions.builder()
                                        .extraBody(Map.of(ArkResponsesSession.CONTEXT, "null"))
                                        .build())
                        .build();
        llm.generateContent(request("hello").toBuilder().config(config).build(), false)
                .blockingFirst();
        assertThat(captured(1).get(0).getPreviousResponseId()).isNull();
    }

    @Test
    void cancellationCancelsUpstreamWithoutRetry() {
        boolean[] cancelled = {false};
        when(service.streamResponse(any()))
                .thenReturn(
                        io.reactivex.Flowable.<StreamEvent>never()
                                .doOnCancel(() -> cancelled[0] = true));
        var subscriber = llm.generateContent(request("hello"), true).test();
        subscriber.cancel();
        assertThat(cancelled[0]).isTrue();
        verify(service).streamResponse(any());
    }

    @Test
    void unrelatedFailureWithValidPreviousIdIsNotRetried() throws Exception {
        when(service.createResponse(any()))
                .thenReturn(raw("r1", "first"))
                .thenThrow(new IllegalStateException("network"));
        var first = request("hello");
        var response = llm.generateContent(first, false).blockingFirst();
        assertThatThrownBy(
                        () ->
                                llm.generateContent(
                                                continuation(first, response, user("next")), false)
                                        .blockingFirst())
                .hasMessage("network");
        captured(2);
    }

    @Test
    void malformedToolArgumentsFailInsteadOfBeingDropped() throws Exception {
        var response = rawTool("r1");
        ((com.volcengine.ark.runtime.model.responses.item.ItemFunctionToolCall)
                        response.getOutput().get(0))
                .setArguments("[]");
        when(service.createResponse(any())).thenReturn(response);
        assertThatThrownBy(() -> llm.generateContent(request("lookup"), false).blockingFirst())
                .isInstanceOf(IllegalArgumentException.class);
    }

    public static class Lookup {
        public static Map<String, Object> lookup(@Schema(name = "key") String key) {
            return Map.of("key", key);
        }
    }

    private static LlmRequest withTool(LlmRequest request) {
        var tool = FunctionTool.create(Lookup.class, "lookup");
        return request.toBuilder().tools(Map.of("lookup", tool)).build();
    }

    private static LlmRequest request(String prompt) {
        return LlmRequest.builder().contents(List.of(user(prompt))).build();
    }

    private static Content user(String text) {
        return Content.builder().role("user").parts(Part.fromText(text)).build();
    }

    private static LlmRequest continuation(LlmRequest first, LlmResponse response, Content next) {
        List<Content> history = new ArrayList<>(first.contents());
        history.add(response.content().orElseThrow());
        history.add(next);
        return ArkResponsesSession.continueFrom(
                first.toBuilder().contents(history).build(), response);
    }

    private static GenerateContentConfig structured() {
        return GenerateContentConfig.builder()
                .temperature(0.25f)
                .topP(0.75f)
                .maxOutputTokens(128)
                .responseMimeType("application/json")
                .responseJsonSchema(
                        Map.of(
                                "type",
                                "OBJECT",
                                "properties",
                                Map.of("ok", Map.of("type", "BOOLEAN")),
                                "required",
                                List.of("ok"),
                                "additionalProperties",
                                false))
                .build();
    }

    private List<CreateResponsesRequest> captured(int count) {
        var captor = ArgumentCaptor.forClass(CreateResponsesRequest.class);
        verify(service, times(count)).createResponse(captor.capture());
        return captor.getAllValues();
    }

    private static JsonNode wire(Object value) throws Exception {
        return ArkService.defaultObjectMapper()
                .readTree(ArkService.defaultObjectMapper().writeValueAsString(value));
    }

    private static ResponseObject raw(String id, String text) throws Exception {
        return ArkService.defaultObjectMapper()
                .readValue(
                        ArkService.defaultObjectMapper()
                                .writeValueAsString(
                                        Map.of(
                                                "id",
                                                id,
                                                "status",
                                                "completed",
                                                "model",
                                                "test-model",
                                                "output",
                                                List.of(
                                                        Map.of(
                                                                "type",
                                                                "message",
                                                                "status",
                                                                "completed",
                                                                "role",
                                                                "assistant",
                                                                "content",
                                                                List.of(
                                                                        Map.of(
                                                                                "type",
                                                                                "output_text",
                                                                                "text",
                                                                                text)))),
                                                "usage",
                                                Map.of(
                                                        "input_tokens",
                                                        10,
                                                        "output_tokens",
                                                        2,
                                                        "total_tokens",
                                                        12))),
                        ResponseObject.class);
    }

    private static ResponseObject rawTool(String id) throws Exception {
        var result = raw(id, "ignored");
        var tool = new com.volcengine.ark.runtime.model.responses.item.ItemFunctionToolCall();
        tool.setCallId("call-1");
        tool.setName("lookup");
        tool.setArguments("{\"key\":\"alpha\"}");
        result.setOutput(List.of(tool));
        return result;
    }

    private static ResponseCompletedEvent done(String id, String text) throws Exception {
        var event = new ResponseCompletedEvent();
        event.setResponse(raw(id, text));
        return event;
    }

    private static OutputTextDeltaEvent delta(String text) {
        var event = new OutputTextDeltaEvent();
        event.setDelta(text);
        return event;
    }

    private static ErrorEvent expiredEvent() {
        var event = new ErrorEvent();
        event.setCode("InvalidParameter.PreviousResponseNotFound");
        event.setMessage("expired");
        return event;
    }

    private static ArkHttpException expired() {
        var details = new ArkAPIError.ArkErrorDetails();
        details.setCode("InvalidParameter.PreviousResponseNotFound");
        details.setMessage("expired");
        return new ArkHttpException(
                new ArkAPIError(details), new Exception("expired"), 400, "request-id");
    }
}
