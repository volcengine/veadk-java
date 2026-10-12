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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.volcengine.ark.runtime.model.Usage;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChoice;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChunk;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionResult;
import com.volcengine.ark.runtime.model.completion.chat.ChatFunctionCall;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.model.completion.chat.ChatToolCall;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.Runner;
import com.volcengine.veadk.utils.JSONUtil;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ArkLlmMappingTest {
    private final ArkService service = mock(ArkService.class);
    private ArkLlm llm;

    @BeforeEach
    void setUp() throws Exception {
        llm = new ArkLlm(ArkLlmConfig.builder().modelName("test-model").apiKey("test-key").build());
        Field field = ArkLlm.class.getDeclaredField("arkService");
        field.setAccessible(true);
        field.set(llm, service);
    }

    @AfterEach
    void tearDown() {
        llm.close();
    }

    @Test
    void nonStreamingRequestTransmitsGenerationParameters() {
        when(service.createChatCompletion(any())).thenReturn(result("ok", null, null));
        llm.generateContent(configuredRequest(), false).blockingFirst();
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(service).createChatCompletion(captor.capture());
        assertParameters(captor.getValue());
        assertThat(captor.getValue().getStreamOptions()).isNull();
    }

    @Test
    void streamingRequestTransmitsParametersAndRequestsUsage() {
        when(service.streamChatCompletion(any()))
                .thenReturn(io.reactivex.Flowable.just(chunk("ok", null, "stop", null)));
        llm.generateContent(configuredRequest(), true).toList().blockingGet();
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(service).streamChatCompletion(captor.capture());
        assertParameters(captor.getValue());
        assertThat(captor.getValue().getStreamOptions().getIncludeUsage()).isTrue();
    }

    @Test
    void absentGenerationParametersRemainUnset() {
        ChatCompletionRequest sent =
                captureRequest(request(Content.fromParts(Part.fromText("hello"))));
        assertThat(sent.getTemperature()).isNull();
        assertThat(sent.getTopP()).isNull();
        assertThat(sent.getMaxTokens()).isNull();
    }

    @Test
    void consecutiveToolTurnsKeepIdsArgumentsAndStructuredResults() throws Exception {
        ChatCompletionRequest sent =
                captureRequest(
                        request(
                                Content.fromParts(Part.fromText("weather")),
                                call("first", "weather", Map.of("city", "Beijing")),
                                reply("first", "weather", Map.of("temperature", 20, "ok", true)),
                                call("second", "weather", Map.of("city", "Shanghai")),
                                reply("second", "weather", Map.of("temperature", 25))));
        assertThat(sent.getMessages()).hasSize(5);
        var first = sent.getMessages().get(1);
        assertThat(first.getRole()).isEqualTo(ChatMessageRole.ASSISTANT);
        assertThat(first.getToolCalls().get(0).getId()).isEqualTo("first");
        assertThat(
                        JSONUtil.parseJson(first.getToolCalls().get(0).getFunction().getArguments())
                                .get("city")
                                .asText())
                .isEqualTo("Beijing");
        assertThat(sent.getMessages().get(2).getRole()).isEqualTo(ChatMessageRole.TOOL);
        assertThat(sent.getMessages().get(2).getToolCallId()).isEqualTo("first");
        assertThat(
                        JSONUtil.parseJson((String) sent.getMessages().get(2).getContent())
                                .get("ok")
                                .asBoolean())
                .isTrue();
        assertThat(sent.getMessages().get(4).getToolCallId()).isEqualTo("second");
    }

    @Test
    void parallelCallsToSameToolAreMatchedByIdEvenWhenResultsAreReversed() {
        Content calls =
                Content.builder()
                        .role("model")
                        .parts(
                                call("a", "lookup", Map.of("id", 1)).parts().orElseThrow().get(0),
                                call("b", "lookup", Map.of("id", 2)).parts().orElseThrow().get(0))
                        .build();
        var sent =
                captureRequest(
                        request(
                                calls,
                                reply("b", "lookup", Map.of("value", 2)),
                                reply("a", "lookup", Map.of("value", 1))));
        assertThat(sent.getMessages().get(0).getToolCalls()).hasSize(2);
        assertThat(sent.getMessages().get(1).getToolCallId()).isEqualTo("b");
        assertThat(sent.getMessages().get(2).getToolCallId()).isEqualTo("a");
    }

    @Test
    void idlessLegacyHistoryIsPairedWhenTheToolNameIsUnambiguous() {
        var sent =
                captureRequest(
                        request(
                                call(null, "lookup", Map.of()),
                                reply(null, "lookup", Map.of("value", 1))));
        String id = sent.getMessages().get(0).getToolCalls().get(0).getId();
        assertThat(id).isNotBlank();
        assertThat(sent.getMessages().get(1).getToolCallId()).isEqualTo(id);
    }

    @Test
    void ambiguousIdlessResultsAreRejectedBeforeSending() {
        Content calls =
                Content.builder()
                        .role("model")
                        .parts(
                                call("a", "lookup", Map.of()).parts().orElseThrow().get(0),
                                call("b", "lookup", Map.of()).parts().orElseThrow().get(0))
                        .build();
        assertThatThrownBy(
                        () ->
                                llm.generateContent(
                                                request(calls, reply(null, "lookup", Map.of())),
                                                false)
                                        .blockingFirst())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void textAlongsideCallsAndResultsIsPreserved() {
        Content callWithText =
                call("a", "lookup", Map.of()).toBuilder()
                        .parts(
                                Part.fromText("Looking up"),
                                call("a", "lookup", Map.of()).parts().orElseThrow().get(0))
                        .build();
        Content resultWithText =
                reply("a", "lookup", Map.of("value", 1)).toBuilder()
                        .parts(
                                Part.fromText("Explain this result"),
                                reply("a", "lookup", Map.of("value", 1))
                                        .parts()
                                        .orElseThrow()
                                        .get(0))
                        .build();
        var messages = captureRequest(request(callWithText, resultWithText)).getMessages();
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).getContent()).isEqualTo("Looking up");
        assertThat(messages.get(0).getToolCalls()).hasSize(1);
        assertThat(messages.get(1).getToolCallId()).isEqualTo("a");
        assertThat(messages.get(2).getRole()).isEqualTo(ChatMessageRole.USER);
        assertThat(messages.get(2).getContent()).isEqualTo("Explain this result");
    }

    @Test
    void callsAndResultsInTheSameTurnAreRejectedInsteadOfReordered() {
        var mixed =
                Content.fromParts(
                        call("a", "lookup", Map.of()).parts().orElseThrow().get(0),
                        reply("a", "lookup", Map.of()).parts().orElseThrow().get(0));
        assertThatThrownBy(() -> llm.generateContent(request(mixed), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("separate Content");
    }

    @Test
    void mismatchedExplicitResultIdIsRejected() {
        assertThatThrownBy(
                        () ->
                                llm.generateContent(
                                        request(
                                                call("a", "lookup", Map.of()),
                                                reply("b", "lookup", Map.of())),
                                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void missingUsageRemainsAbsent() {
        when(service.createChatCompletion(any())).thenReturn(result("ok", null, null));
        assertThat(llm.generateContent(request(), false).blockingFirst().usageMetadata()).isEmpty();
    }

    @Test
    void nonStreamingToolResponsePreservesCallIdAndUsage() {
        when(service.createChatCompletion(any()))
                .thenReturn(
                        result(
                                null,
                                List.of(tool(0, "call-42", "lookup", "{\"id\":42}")),
                                new Usage(8, 3, 11)));
        LlmResponse response = llm.generateContent(request(), false).blockingFirst();
        assertThat(
                        response.content()
                                .orElseThrow()
                                .parts()
                                .orElseThrow()
                                .get(0)
                                .functionCall()
                                .orElseThrow()
                                .id())
                .contains("call-42");
        assertUsage(response, 8, 3, 11);
    }

    @Test
    void usageSurvivesAnEmptyChoicesTrailerAndIsOnlyEmittedOnce() {
        when(service.streamChatCompletion(any()))
                .thenReturn(
                        io.reactivex.Flowable.just(
                                chunk("hello", null, null, new Usage(10, 1, 11)),
                                chunk(null, null, "stop", null),
                                usageChunk(new Usage(10, 2, 12))));
        List<LlmResponse> responses = llm.generateContent(request(), true).toList().blockingGet();
        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).partial()).contains(true);
        assertThat(responses.get(0).usageMetadata()).isEmpty();
        assertThat(responses.get(1).partial()).contains(false);
        assertUsage(responses.get(1), 10, 2, 12);
        assertThat(responses.get(1).content().orElseThrow().parts().orElseThrow().get(0).text())
                .contains("hello");
    }

    @Test
    void toolOnlyStreamAccumulatesParallelFragmentsAndCanBeResubscribed() {
        var chunks =
                List.of(
                        chunk(
                                null,
                                List.of(
                                        tool(1, "b", "lookup", "{\"id\":"),
                                        tool(0, "a", "lookup", "{\"id\":")),
                                null,
                                null),
                        chunk(
                                null,
                                List.of(tool(0, null, null, "1}"), tool(1, null, null, "2}")),
                                null,
                                null),
                        chunk(null, null, "tool_calls", null),
                        usageChunk(new Usage(10, 4, 14)));
        when(service.streamChatCompletion(any()))
                .thenReturn(io.reactivex.Flowable.fromIterable(chunks));
        var stream = llm.generateContent(request(), true);
        for (int iteration = 0; iteration < 2; iteration++) {
            var responses = stream.toList().blockingGet();
            assertThat(responses).hasSize(1);
            var parts = responses.get(0).content().orElseThrow().parts().orElseThrow();
            assertThat(parts).hasSize(2);
            assertThat(parts.get(0).functionCall().orElseThrow().id()).contains("a");
            assertThat(parts.get(0).functionCall().orElseThrow().args().orElseThrow())
                    .containsEntry("id", 1);
            assertThat(parts.get(1).functionCall().orElseThrow().id()).contains("b");
            assertUsage(responses.get(0), 10, 4, 14);
        }
    }

    @Test
    void streamFailureAfterPartialOutputDoesNotEmitACompletedResponse() {
        when(service.streamChatCompletion(any()))
                .thenReturn(
                        io.reactivex.Flowable.concat(
                                io.reactivex.Flowable.just(
                                        chunk(
                                                "This is more than thirty characters of partial"
                                                        + " output.",
                                                null,
                                                null,
                                                null)),
                                io.reactivex.Flowable.error(
                                        new IllegalStateException("connection lost"))));
        TestSubscriber<LlmResponse> subscriber = llm.generateContent(request(), true).test();
        subscriber.assertError(IllegalStateException.class).assertValueCount(1);
        assertThat(subscriber.values().get(0).partial()).contains(true);
    }

    @Test
    void truncatedStreamWithoutFinishReasonDoesNotSilentlySucceed() {
        when(service.streamChatCompletion(any()))
                .thenReturn(io.reactivex.Flowable.just(chunk("unfinished", null, null, null)));
        llm.generateContent(request(), true).test().assertError(IllegalStateException.class);
    }

    @Test
    void malformedToolArgumentsAreNotSilentlyDropped() {
        when(service.createChatCompletion(any()))
                .thenReturn(result(null, List.of(tool(0, "bad", "lookup", "{not-json")), null));
        assertThatThrownBy(() -> llm.generateContent(request(), false).blockingFirst())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runnerExecutesConsecutiveToolsAndReturnsTheirIdsInHistory() {
        when(service.createChatCompletion(any()))
                .thenReturn(
                        result(
                                null,
                                List.of(tool(0, "first", "lookup", "{\"key\":\"alpha\"}")),
                                new Usage(7, 2, 9)),
                        result(
                                null,
                                List.of(tool(0, "second", "lookup", "{\"key\":\"beta\"}")),
                                new Usage(12, 2, 14)),
                        result("done", null, new Usage(17, 2, 19)));
        Agent agent =
                Agent.builder()
                        .name("tool_agent")
                        .model(llm)
                        .tools(FunctionTool.create(LookupTool.class, "lookup"))
                        .build();
        assertThat(new Runner(agent).run("user", "session", "Look up alpha then beta"))
                .isEqualTo("done");
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(service, times(3)).createChatCompletion(captor.capture());
        var messages = captor.getAllValues().get(2).getMessages();
        assertThat(
                        messages.stream()
                                .filter(message -> message.getRole() == ChatMessageRole.TOOL)
                                .map(ChatMessage::getToolCallId)
                                .toList())
                .containsExactly("first", "second");
        assertThat(
                        messages.stream()
                                .filter(message -> message.getRole() == ChatMessageRole.TOOL)
                                .map(message -> (String) message.getContent())
                                .toList())
                .anySatisfy(text -> assertThat(text).contains("alpha"))
                .anySatisfy(text -> assertThat(text).contains("beta"));
    }

    public static class LookupTool {
        public static Map<String, Object> lookup(@Schema(name = "key") String key) {
            return Map.of("key", key, "value", 1);
        }
    }

    @Test
    void usageReachesThePersistedAdkEvent() {
        when(service.createChatCompletion(any()))
                .thenReturn(result("ok", null, new Usage(7, 2, 9)));
        Agent agent = Agent.builder().name("usage_agent").model(llm).build();
        Runner runner = new Runner(agent);
        assertThat(runner.run("user", "session", "hello")).isEqualTo("ok");
        var session =
                runner.sessionService()
                        .getSession("usage_agent", "user", "session", Optional.empty())
                        .blockingGet();
        assertThat(session.events())
                .anySatisfy(
                        event -> {
                            assertThat(event.usageMetadata()).isPresent();
                            assertThat(event.usageMetadata().orElseThrow().totalTokenCount())
                                    .contains(9);
                        });
    }

    private ChatCompletionRequest captureRequest(LlmRequest request) {
        when(service.createChatCompletion(any())).thenReturn(result("ok", null, null));
        llm.generateContent(request, false).blockingFirst();
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(service).createChatCompletion(captor.capture());
        return captor.getValue();
    }

    private static LlmRequest request(Content... contents) {
        return LlmRequest.builder().model("test-model").contents(List.of(contents)).build();
    }

    private static LlmRequest configuredRequest() {
        return request(Content.fromParts(Part.fromText("hello"))).toBuilder()
                .config(
                        GenerateContentConfig.builder()
                                .temperature(0.25f)
                                .topP(0.75f)
                                .maxOutputTokens(128)
                                .build())
                .build();
    }

    private static void assertParameters(ChatCompletionRequest request) {
        assertThat(request.getTemperature()).isEqualTo(0.25);
        assertThat(request.getTopP()).isEqualTo(0.75);
        assertThat(request.getMaxTokens()).isEqualTo(128);
    }

    private static void assertUsage(LlmResponse response, int prompt, int output, int total) {
        var usage = response.usageMetadata().orElseThrow();
        assertThat(usage.promptTokenCount()).contains(prompt);
        assertThat(usage.candidatesTokenCount()).contains(output);
        assertThat(usage.totalTokenCount()).contains(total);
    }

    private static Content call(String id, String name, Map<String, Object> args) {
        var function = FunctionCall.builder().name(name).args(args);
        if (id != null) function.id(id);
        return Content.builder()
                .role("model")
                .parts(Part.builder().functionCall(function.build()).build())
                .build();
    }

    private static Content reply(String id, String name, Map<String, Object> response) {
        var function = FunctionResponse.builder().name(name).response(response);
        if (id != null) function.id(id);
        return Content.builder()
                .role("user")
                .parts(Part.builder().functionResponse(function.build()).build())
                .build();
    }

    private static ChatToolCall tool(int index, String id, String name, String args) {
        ChatToolCall call = new ChatToolCall(id, "function", new ChatFunctionCall(name, args));
        call.setIndex(index);
        return call;
    }

    private static ChatCompletionChunk chunk(
            String text, List<ChatToolCall> calls, String finish, Usage usage) {
        ChatMessage message = new ChatMessage();
        message.setContent(text);
        message.setToolCalls(calls);
        ChatCompletionChoice choice = new ChatCompletionChoice();
        choice.setMessage(message);
        choice.setFinishReason(finish);
        ChatCompletionChunk chunk = new ChatCompletionChunk();
        chunk.setChoices(List.of(choice));
        chunk.setUsage(usage);
        return chunk;
    }

    private static ChatCompletionChunk usageChunk(Usage usage) {
        ChatCompletionChunk chunk = new ChatCompletionChunk();
        chunk.setChoices(List.of());
        chunk.setUsage(usage);
        return chunk;
    }

    private static ChatCompletionResult result(String text, List<ChatToolCall> calls, Usage usage) {
        ChatCompletionResult result = new ChatCompletionResult();
        result.setChoices(
                chunk(text, calls, calls == null ? "stop" : "tool_calls", usage).getChoices());
        result.setUsage(usage);
        return result;
    }
}
