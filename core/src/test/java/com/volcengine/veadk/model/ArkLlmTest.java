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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.google.genai.types.VideoMetadata;
import com.volcengine.ark.runtime.model.CompletionTokensDetails;
import com.volcengine.ark.runtime.model.PromptTokensDetails;
import com.volcengine.ark.runtime.model.Usage;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChoice;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChunk;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionContentPart;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionResult;
import com.volcengine.ark.runtime.model.completion.chat.ChatFunctionCall;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.model.completion.chat.ChatToolCall;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.veadk.utils.EnvUtil;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArkLlmTest {

    @Mock private ArkService arkService;

    private ArkLlm arkLlm;

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        try (MockedStatic<EnvUtil> mocked = mockStatic(EnvUtil.class)) {
            mocked.when(EnvUtil::getAgentApiKey).thenReturn("test-api-key");
            arkLlm = new ArkLlm("test-model");
        }
        Field field = ArkLlm.class.getDeclaredField("arkService");
        field.setAccessible(true);
        field.set(arkLlm, arkService);
    }

    @Test
    void generateContent_nonStreaming_textResponse() throws InterruptedException {
        LlmRequest llmRequest =
                LlmRequest.builder()
                        .model("test-model")
                        .contents(
                                Collections.singletonList(
                                        Content.builder()
                                                .role("user")
                                                .parts(Part.fromText("Hello"))
                                                .build()))
                        .build();

        ChatCompletionResult mockResult = createMockTextResult("Hi there!");
        Usage usage = new Usage(5, 2, 7);
        PromptTokensDetails promptTokensDetails = new PromptTokensDetails();
        promptTokensDetails.setCachedTokens(3);
        usage.setPromptTokensDetails(promptTokensDetails);
        CompletionTokensDetails completionTokensDetails = new CompletionTokensDetails();
        completionTokensDetails.setReasoningTokens(1);
        usage.setCompletionTokensDetails(completionTokensDetails);
        mockResult.setUsage(usage);
        when(arkService.createChatCompletion(any(ChatCompletionRequest.class)))
                .thenReturn(mockResult);

        Flowable<LlmResponse> responseFlowable = arkLlm.generateContent(llmRequest, false);
        TestSubscriber<LlmResponse> testSubscriber = responseFlowable.test();

        testSubscriber.awaitDone(5, TimeUnit.SECONDS);
        testSubscriber.assertNoErrors();
        testSubscriber.assertValueCount(1);
        LlmResponse response = testSubscriber.values().get(0);
        assertTrue(response.content().isPresent());
        assertTrue(response.content().get().parts().isPresent());
        assertEquals("Hi there!", response.content().get().parts().get().get(0).text().get());
        assertTrue(response.finishReason().isPresent());
        assertEquals(FinishReason.Known.STOP, response.finishReason().get().knownEnum());
        assertTrue(response.usageMetadata().isPresent());
        GenerateContentResponseUsageMetadata usageMetadata = response.usageMetadata().get();
        assertEquals(Integer.valueOf(5), usageMetadata.promptTokenCount().get());
        assertEquals(Integer.valueOf(2), usageMetadata.candidatesTokenCount().get());
        assertEquals(Integer.valueOf(7), usageMetadata.totalTokenCount().get());
        assertEquals(Integer.valueOf(3), usageMetadata.cachedContentTokenCount().get());
        assertEquals(Integer.valueOf(1), usageMetadata.thoughtsTokenCount().get());
    }

    @Test
    void generateContent_nonStreaming_toolCallResponse() throws InterruptedException {
        LlmRequest llmRequest =
                LlmRequest.builder()
                        .model("test-model")
                        .contents(
                                Collections.singletonList(
                                        Content.builder()
                                                .role("user")
                                                .parts(Part.fromText("Search for cats"))
                                                .build()))
                        .build();

        ChatFunctionCall function = new ChatFunctionCall();
        function.setName("search");
        function.setArguments("{\"query\":\"cats\"}");
        ChatToolCall toolCall = new ChatToolCall();
        toolCall.setId("tool-123");
        toolCall.setType("function");
        toolCall.setFunction(function);
        ChatCompletionResult mockResult =
                createMockToolCallResult(Collections.singletonList(toolCall));
        when(arkService.createChatCompletion(any(ChatCompletionRequest.class)))
                .thenReturn(mockResult);

        Flowable<LlmResponse> responseFlowable = arkLlm.generateContent(llmRequest, false);
        TestSubscriber<LlmResponse> testSubscriber = responseFlowable.test();

        testSubscriber.awaitDone(5, TimeUnit.SECONDS);
        testSubscriber.assertNoErrors();
        testSubscriber.assertValueCount(1);
        LlmResponse response = testSubscriber.values().get(0);

        assertTrue(response.content().isPresent());
        assertTrue(response.content().get().parts().isPresent());
        assertTrue(response.content().get().parts().get().get(0).functionCall().isPresent());
        FunctionCall fc = response.content().get().parts().get().get(0).functionCall().get();
        assertTrue(fc.name().isPresent());
        assertEquals("search", fc.name().get());
        assertTrue(fc.id().isPresent());
        assertEquals("tool-123", fc.id().get());
        assertTrue(fc.args().isPresent());
        assertEquals(Map.of("query", "cats"), fc.args().get());
    }

    @Test
    void generateContent_mapsFunctionCallAndResponseHistoryToArkToolMessages()
            throws InterruptedException {
        Content assistantCall =
                Content.builder()
                        .role("model")
                        .parts(
                                List.of(
                                        Part.fromText("Searching"),
                                        Part.builder()
                                                .functionCall(
                                                        FunctionCall.builder()
                                                                .id("call-1")
                                                                .name("search")
                                                                .args(Map.of("query", "cats"))
                                                                .build())
                                                .build()))
                        .build();
        Content toolResult =
                Content.builder()
                        .role("user")
                        .parts(
                                Part.builder()
                                        .functionResponse(
                                                FunctionResponse.builder()
                                                        .id("call-1")
                                                        .name("search")
                                                        .response(Map.of("result", "cats"))
                                                        .build())
                                        .build())
                        .build();
        LlmRequest llmRequest =
                LlmRequest.builder()
                        .model("test-model")
                        .contents(List.of(assistantCall, toolResult))
                        .build();
        when(arkService.createChatCompletion(any(ChatCompletionRequest.class)))
                .thenReturn(createMockTextResult("done"));

        TestSubscriber<LlmResponse> testSubscriber =
                arkLlm.generateContent(llmRequest, false).test();

        testSubscriber.awaitDone(5, TimeUnit.SECONDS);
        testSubscriber.assertNoErrors();

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(arkService).createChatCompletion(captor.capture());
        List<ChatMessage> messages = captor.getValue().getMessages();
        assertEquals(2, messages.size());
        assertEquals(ChatMessageRole.ASSISTANT, messages.get(0).getRole());
        assertEquals("Searching", messages.get(0).getContent());
        assertEquals("call-1", messages.get(0).getToolCalls().get(0).getId());
        assertEquals("function", messages.get(0).getToolCalls().get(0).getType());
        assertEquals("search", messages.get(0).getToolCalls().get(0).getFunction().getName());
        assertEquals(
                "{\"query\":\"cats\"}",
                messages.get(0).getToolCalls().get(0).getFunction().getArguments());
        assertEquals(ChatMessageRole.TOOL, messages.get(1).getRole());
        assertEquals("call-1", messages.get(1).getToolCallId());
        assertEquals("search", messages.get(1).getName());
        assertEquals("{\"result\":\"cats\"}", messages.get(1).getContent());
    }

    @Test
    void generateContent_mapsGenerationConfigToArkRequest() throws InterruptedException {
        GenerateContentConfig config =
                GenerateContentConfig.builder()
                        .temperature(0.2f)
                        .topP(0.9f)
                        .maxOutputTokens(128)
                        .stopSequences(List.of("END"))
                        .presencePenalty(0.1f)
                        .frequencyPenalty(0.3f)
                        .candidateCount(2)
                        .responseLogprobs(true)
                        .logprobs(4)
                        .build();
        LlmRequest llmRequest =
                LlmRequest.builder()
                        .model("test-model")
                        .config(config)
                        .contents(
                                Collections.singletonList(
                                        Content.builder()
                                                .role("user")
                                                .parts(Part.fromText("Hello"))
                                                .build()))
                        .build();
        when(arkService.createChatCompletion(any(ChatCompletionRequest.class)))
                .thenReturn(createMockTextResult("configured response"));

        TestSubscriber<LlmResponse> testSubscriber =
                arkLlm.generateContent(llmRequest, false).test();

        testSubscriber.awaitDone(5, TimeUnit.SECONDS);
        testSubscriber.assertNoErrors();

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(arkService).createChatCompletion(captor.capture());
        ChatCompletionRequest request = captor.getValue();
        assertEquals(0.2, request.getTemperature(), 0.0001);
        assertEquals(0.9, request.getTopP(), 0.0001);
        assertEquals(Integer.valueOf(128), request.getMaxTokens());
        assertEquals(List.of("END"), request.getStop());
        assertEquals(0.1, request.getPresencePenalty(), 0.0001);
        assertEquals(0.3, request.getFrequencyPenalty(), 0.0001);
        assertEquals(Integer.valueOf(2), request.getN());
        assertEquals(Boolean.TRUE, request.getLogprobs());
        assertEquals(Integer.valueOf(4), request.getTopLogprobs());
    }

    @Test
    void generateContent_mapsJsonMimeTypeToArkResponseFormat() throws InterruptedException {
        GenerateContentConfig config =
                GenerateContentConfig.builder().responseMimeType("application/json").build();
        LlmRequest llmRequest =
                LlmRequest.builder()
                        .model("test-model")
                        .config(config)
                        .contents(
                                Collections.singletonList(
                                        Content.builder()
                                                .role("user")
                                                .parts(Part.fromText("Hello"))
                                                .build()))
                        .build();
        when(arkService.createChatCompletion(any(ChatCompletionRequest.class)))
                .thenReturn(createMockTextResult("{\"answer\":\"ok\"}"));

        TestSubscriber<LlmResponse> testSubscriber =
                arkLlm.generateContent(llmRequest, false).test();

        testSubscriber.awaitDone(5, TimeUnit.SECONDS);
        testSubscriber.assertNoErrors();

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(arkService).createChatCompletion(captor.capture());
        assertEquals("json_object", captor.getValue().getResponseFormat().getType());
    }

    @Test
    void generateContent_mapsResponseSchemaToArkJsonSchemaFormat() throws InterruptedException {
        Schema schema =
                Schema.builder()
                        .title("Answer")
                        .description("Structured answer")
                        .type(Type.Known.OBJECT)
                        .properties(
                                Map.of(
                                        "answer",
                                        Schema.builder()
                                                .type(Type.Known.STRING)
                                                .description("Answer text")
                                                .build()))
                        .required("answer")
                        .build();
        GenerateContentConfig config =
                GenerateContentConfig.builder().responseSchema(schema).build();
        LlmRequest llmRequest =
                LlmRequest.builder()
                        .model("test-model")
                        .config(config)
                        .contents(
                                Collections.singletonList(
                                        Content.builder()
                                                .role("user")
                                                .parts(Part.fromText("Hello"))
                                                .build()))
                        .build();
        when(arkService.createChatCompletion(any(ChatCompletionRequest.class)))
                .thenReturn(createMockTextResult("{\"answer\":\"ok\"}"));

        TestSubscriber<LlmResponse> testSubscriber =
                arkLlm.generateContent(llmRequest, false).test();

        testSubscriber.awaitDone(5, TimeUnit.SECONDS);
        testSubscriber.assertNoErrors();

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(arkService).createChatCompletion(captor.capture());
        ChatCompletionRequest request = captor.getValue();
        assertEquals("json_schema", request.getResponseFormat().getType());
        assertEquals("Answer", request.getResponseFormat().getJsonSchema().getName());
        assertTrue(request.getResponseFormat().getJsonSchema().isStrict());
        assertEquals(
                "object",
                request.getResponseFormat().getJsonSchema().getSchema().get("type").asText());
        assertEquals(
                "string",
                request.getResponseFormat()
                        .getJsonSchema()
                        .getSchema()
                        .get("properties")
                        .get("answer")
                        .get("type")
                        .asText());
    }

    @Test
    void generateContent_mapsResponseJsonSchemaToArkJsonSchemaFormat() throws InterruptedException {
        GenerateContentConfig config =
                GenerateContentConfig.builder()
                        .responseJsonSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("answer", Map.of("type", "string")),
                                        "required",
                                        List.of("answer")))
                        .build();
        LlmRequest llmRequest = requestWithConfig(config);
        when(arkService.createChatCompletion(any(ChatCompletionRequest.class)))
                .thenReturn(createMockTextResult("{\"answer\":\"ok\"}"));

        arkLlm.generateContent(llmRequest, false).test().awaitDone(5, TimeUnit.SECONDS);

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(arkService).createChatCompletion(captor.capture());
        assertEquals("json_schema", captor.getValue().getResponseFormat().getType());
        assertEquals(
                "string",
                captor.getValue()
                        .getResponseFormat()
                        .getJsonSchema()
                        .getSchema()
                        .get("properties")
                        .get("answer")
                        .get("type")
                        .asText());
    }

    @Test
    void generateContent_mapsToolParametersJsonSchema() throws InterruptedException {
        BaseTool tool = mock(BaseTool.class);
        when(tool.name()).thenReturn("search");
        when(tool.description()).thenReturn("Search documents");
        when(tool.declaration())
                .thenReturn(
                        Optional.of(
                                FunctionDeclaration.builder()
                                        .name("search")
                                        .parametersJsonSchema(
                                                Map.of(
                                                        "type",
                                                        "OBJECT",
                                                        "properties",
                                                        Map.of("query", Map.of("type", "STRING"))))
                                        .build()));
        LlmRequest llmRequest =
                LlmRequest.builder()
                        .model("test-model")
                        .contents(
                                List.of(
                                        Content.builder()
                                                .role("user")
                                                .parts(Part.fromText("Hello"))
                                                .build()))
                        .appendTools(List.of(tool))
                        .build();
        when(arkService.createChatCompletion(any(ChatCompletionRequest.class)))
                .thenReturn(createMockTextResult("ok"));

        arkLlm.generateContent(llmRequest, false).test().awaitDone(5, TimeUnit.SECONDS);

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(arkService).createChatCompletion(captor.capture());
        assertEquals(1, captor.getValue().getTools().size());
        assertEquals(
                "object",
                captor.getValue()
                        .getTools()
                        .get(0)
                        .getFunction()
                        .getParameters()
                        .get("type")
                        .asText());
        assertEquals(
                "string",
                captor.getValue()
                        .getTools()
                        .get(0)
                        .getFunction()
                        .getParameters()
                        .get("properties")
                        .get("query")
                        .get("type")
                        .asText());
    }

    @Test
    void generateContent_keepsTextOnlyMessageContentAsString() throws InterruptedException {
        LlmRequest llmRequest =
                LlmRequest.builder()
                        .model("test-model")
                        .contents(
                                Collections.singletonList(
                                        Content.builder()
                                                .role("user")
                                                .parts(Part.fromText("plain text"))
                                                .build()))
                        .build();
        when(arkService.createChatCompletion(any(ChatCompletionRequest.class)))
                .thenReturn(createMockTextResult("ok"));

        TestSubscriber<LlmResponse> testSubscriber =
                arkLlm.generateContent(llmRequest, false).test();

        testSubscriber.awaitDone(5, TimeUnit.SECONDS);
        testSubscriber.assertNoErrors();
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(arkService).createChatCompletion(captor.capture());
        assertEquals("plain text", captor.getValue().getMessages().get(0).getContent());
    }

    @Test
    void generateContent_mapsInlineImageAndFileVideoToArkContentParts()
            throws InterruptedException {
        Part imagePart =
                Part.builder()
                        .inlineData(
                                Blob.builder()
                                        .mimeType("image/png")
                                        .data(new byte[] {1, 2, 3})
                                        .build())
                        .build();
        Part videoPart =
                Part.builder()
                        .fileData(
                                FileData.builder()
                                        .mimeType("video/mp4")
                                        .fileUri("https://example.com/video.mp4")
                                        .build())
                        .videoMetadata(VideoMetadata.builder().fps(2.5).build())
                        .build();
        LlmRequest llmRequest =
                LlmRequest.builder()
                        .model("test-model")
                        .contents(
                                Collections.singletonList(
                                        Content.builder()
                                                .role("user")
                                                .parts(
                                                        List.of(
                                                                Part.fromText("describe"),
                                                                imagePart,
                                                                videoPart))
                                                .build()))
                        .build();
        when(arkService.createChatCompletion(any(ChatCompletionRequest.class)))
                .thenReturn(createMockTextResult("ok"));

        TestSubscriber<LlmResponse> testSubscriber =
                arkLlm.generateContent(llmRequest, false).test();

        testSubscriber.awaitDone(5, TimeUnit.SECONDS);
        testSubscriber.assertNoErrors();
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(arkService).createChatCompletion(captor.capture());

        Object content = captor.getValue().getMessages().get(0).getContent();
        assertTrue(content instanceof List<?>);
        @SuppressWarnings("unchecked")
        List<ChatCompletionContentPart> parts = (List<ChatCompletionContentPart>) content;
        assertEquals(3, parts.size());
        assertEquals("text", parts.get(0).getType());
        assertEquals("describe", parts.get(0).getText());
        assertEquals("image_url", parts.get(1).getType());
        assertEquals("auto", parts.get(1).getImageUrl().getDetail());
        assertEquals("data:image/png;base64,AQID", parts.get(1).getImageUrl().getUrl());
        assertEquals("video_url", parts.get(2).getType());
        assertEquals("https://example.com/video.mp4", parts.get(2).getVideoUrl().getUrl());
        assertEquals(2.5, parts.get(2).getVideoUrl().getFps(), 0.0001);
    }

    @Test
    void generateContent_streaming_textResponse() throws InterruptedException {
        LlmRequest llmRequest =
                LlmRequest.builder()
                        .model("test-model")
                        .contents(
                                Collections.singletonList(
                                        Content.builder()
                                                .role("user")
                                                .parts(Part.fromText("Hello"))
                                                .build()))
                        .build();

        io.reactivex.Flowable<ChatCompletionChunk> chunkFlowable =
                io.reactivex.Flowable.just(
                        createMockTextChunk("Hello "),
                        createMockTextChunk("World!"),
                        createStopChunk());
        when(arkService.streamChatCompletion(any(ChatCompletionRequest.class)))
                .thenReturn(chunkFlowable);

        Flowable<LlmResponse> responseFlowable = arkLlm.generateContent(llmRequest, true);
        TestSubscriber<LlmResponse> testSubscriber = responseFlowable.test();

        testSubscriber.awaitDone(5, TimeUnit.SECONDS);
        testSubscriber.assertNoErrors();
        testSubscriber.assertValueCount(2); // Should be 2: one partial, one final

        // Check partial response
        LlmResponse partialResponse = testSubscriber.values().get(0);
        assertTrue(partialResponse.partial().isPresent() && partialResponse.partial().get());
        assertTrue(partialResponse.content().isPresent());
        assertTrue(partialResponse.content().get().parts().isPresent());
        assertEquals(
                "Hello World!", partialResponse.content().get().parts().get().get(0).text().get());

        // Check final response
        LlmResponse finalResponse = testSubscriber.values().get(1);
        assertTrue(finalResponse.partial().isPresent() && !finalResponse.partial().get());
        assertTrue(finalResponse.content().isPresent());
        assertTrue(finalResponse.content().get().parts().isPresent());
        assertEquals(
                "Hello World!", finalResponse.content().get().parts().get().get(0).text().get());
    }

    @Test
    void generateContent_streaming_parallelToolCallsWithoutText() throws InterruptedException {
        LlmRequest llmRequest =
                LlmRequest.builder()
                        .model("test-model")
                        .contents(
                                List.of(
                                        Content.builder()
                                                .role("user")
                                                .parts(Part.fromText("Use tools"))
                                                .build()))
                        .build();
        Usage usage = new Usage(3, 4, 7);
        io.reactivex.Flowable<ChatCompletionChunk> chunks =
                io.reactivex.Flowable.just(
                        createToolCallChunk(
                                List.of(
                                        toolCall(0, "call-1", "first", "{\"value\":"),
                                        toolCall(1, "call-2", "second", "{\"value\":"))),
                        createToolCallChunk(
                                List.of(
                                        toolCall(0, null, null, "1}"),
                                        toolCall(1, null, null, "2}"))),
                        createStopChunk("tool_calls"),
                        createUsageChunk(usage));
        when(arkService.streamChatCompletion(any(ChatCompletionRequest.class))).thenReturn(chunks);

        TestSubscriber<LlmResponse> subscriber = arkLlm.generateContent(llmRequest, true).test();

        subscriber.awaitDone(5, TimeUnit.SECONDS);
        subscriber.assertNoErrors();
        subscriber.assertValueCount(1);
        LlmResponse response = subscriber.values().get(0);
        assertTrue(response.partial().isPresent() && !response.partial().get());
        List<Part> parts = response.content().get().parts().get();
        assertEquals(2, parts.size());
        assertEquals("call-1", parts.get(0).functionCall().get().id().get());
        assertEquals(Map.of("value", 1), parts.get(0).functionCall().get().args().get());
        assertEquals("call-2", parts.get(1).functionCall().get().id().get());
        assertEquals(Map.of("value", 2), parts.get(1).functionCall().get().args().get());
        assertEquals(FinishReason.Known.STOP, response.finishReason().get().knownEnum());
        assertEquals(Integer.valueOf(7), response.usageMetadata().get().totalTokenCount().get());

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(arkService).streamChatCompletion(captor.capture());
        assertEquals(Boolean.TRUE, captor.getValue().getStreamOptions().getIncludeUsage());
    }

    @Test
    void generateContent_nonStreaming_usesFallbackWhenPrimaryFails() throws Exception {
        ArkLlm fallbackLlm =
                new ArkLlm(List.of("primary-model", "fallback-model"), "test-api-key", null);
        injectArkService(fallbackLlm);
        LlmRequest llmRequest =
                LlmRequest.builder()
                        .model("primary-model")
                        .contents(
                                Collections.singletonList(
                                        Content.builder()
                                                .role("user")
                                                .parts(Part.fromText("Hello"))
                                                .build()))
                        .build();

        when(arkService.createChatCompletion(any(ChatCompletionRequest.class)))
                .thenThrow(new RuntimeException("primary failed"))
                .thenReturn(createMockTextResult("fallback response"));

        TestSubscriber<LlmResponse> testSubscriber =
                fallbackLlm.generateContent(llmRequest, false).test();

        testSubscriber.awaitDone(5, TimeUnit.SECONDS);
        testSubscriber.assertNoErrors();
        testSubscriber.assertValueCount(1);
        assertEquals(
                "fallback response",
                testSubscriber.values().get(0).content().get().parts().get().get(0).text().get());

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(arkService, times(2)).createChatCompletion(captor.capture());
        assertEquals(
                List.of("primary-model", "fallback-model"),
                captor.getAllValues().stream().map(ChatCompletionRequest::getModel).toList());
    }

    @Test
    void generateContent_streaming_doesNotFallbackAfterEmittingOutput() throws Exception {
        ArkLlm fallbackLlm =
                new ArkLlm(List.of("primary-model", "fallback-model"), "test-api-key", null);
        injectArkService(fallbackLlm);
        LlmRequest llmRequest =
                LlmRequest.builder()
                        .contents(
                                Collections.singletonList(
                                        Content.builder()
                                                .role("user")
                                                .parts(Part.fromText("Hello"))
                                                .build()))
                        .build();
        RuntimeException streamFailure = new RuntimeException("stream interrupted");
        io.reactivex.Flowable<ChatCompletionChunk> failingStream =
                io.reactivex.Flowable.concat(
                        io.reactivex.Flowable.just(
                                createMockTextChunk("abcdefghijklmnopqrstuvwxyz12345")),
                        io.reactivex.Flowable.error(streamFailure));
        when(arkService.streamChatCompletion(any(ChatCompletionRequest.class)))
                .thenReturn(failingStream);

        TestSubscriber<LlmResponse> testSubscriber =
                fallbackLlm.generateContent(llmRequest, true).test();

        testSubscriber.awaitDone(5, TimeUnit.SECONDS);
        testSubscriber.assertValueCount(1);
        testSubscriber.assertError(streamFailure);
        assertTrue(testSubscriber.values().get(0).partial().orElse(false));
        verify(arkService, times(1)).streamChatCompletion(any(ChatCompletionRequest.class));
    }

    @Test
    void generateContent_respectsExplicitRequestModelWithoutFallbacks() throws Exception {
        ArkLlm fallbackLlm =
                new ArkLlm(List.of("primary-model", "fallback-model"), "test-api-key", null);
        injectArkService(fallbackLlm);
        LlmRequest llmRequest =
                LlmRequest.builder()
                        .model("request-model")
                        .contents(
                                Collections.singletonList(
                                        Content.builder()
                                                .role("user")
                                                .parts(Part.fromText("Hello"))
                                                .build()))
                        .build();
        when(arkService.createChatCompletion(any(ChatCompletionRequest.class)))
                .thenReturn(createMockTextResult("request response"));

        TestSubscriber<LlmResponse> testSubscriber =
                fallbackLlm.generateContent(llmRequest, false).test();

        testSubscriber.awaitDone(5, TimeUnit.SECONDS);
        testSubscriber.assertNoErrors();
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(arkService).createChatCompletion(captor.capture());
        assertEquals("request-model", captor.getValue().getModel());
    }

    private LlmRequest requestWithConfig(GenerateContentConfig config) {
        return LlmRequest.builder()
                .model("test-model")
                .config(config)
                .contents(
                        List.of(
                                Content.builder()
                                        .role("user")
                                        .parts(Part.fromText("Hello"))
                                        .build()))
                .build();
    }

    private ChatCompletionResult createMockTextResult(String content) {
        ChatCompletionResult mockResult = new ChatCompletionResult();
        ChatCompletionChoice mockChoice = new ChatCompletionChoice();
        ChatMessage mockMessage = new ChatMessage();
        mockMessage.setContent(content);
        mockChoice.setMessage(mockMessage);
        mockChoice.setFinishReason("stop");
        mockResult.setChoices(Collections.singletonList(mockChoice));
        return mockResult;
    }

    private ChatCompletionResult createMockToolCallResult(List<ChatToolCall> toolCalls) {
        ChatCompletionResult mockResult = new ChatCompletionResult();
        ChatCompletionChoice mockChoice = new ChatCompletionChoice();
        ChatMessage mockMessage = new ChatMessage();
        mockMessage.setToolCalls(
                toolCalls.stream()
                        .map(
                                tc -> {
                                    ChatToolCall toolCall = new ChatToolCall();
                                    toolCall.setId(tc.getId());
                                    toolCall.setType(tc.getType());
                                    toolCall.setFunction(tc.getFunction());
                                    return toolCall;
                                })
                        .collect(java.util.stream.Collectors.toList()));
        mockChoice.setMessage(mockMessage);
        mockChoice.setFinishReason("tool_calls");
        mockResult.setChoices(Collections.singletonList(mockChoice));
        return mockResult;
    }

    private ChatCompletionChunk createMockTextChunk(String content) {
        ChatCompletionChunk chunk = new ChatCompletionChunk();
        ChatCompletionChoice choice = new ChatCompletionChoice();
        ChatMessage message = new ChatMessage();
        message.setContent(content);
        choice.setMessage(message);
        chunk.setChoices(Collections.singletonList(choice));
        return chunk;
    }

    private ChatCompletionChunk createToolCallChunk(List<ChatToolCall> toolCalls) {
        ChatCompletionChunk chunk = new ChatCompletionChunk();
        ChatCompletionChoice choice = new ChatCompletionChoice();
        ChatMessage message = new ChatMessage();
        message.setToolCalls(toolCalls);
        choice.setMessage(message);
        chunk.setChoices(List.of(choice));
        return chunk;
    }

    private ChatToolCall toolCall(
            int index, String id, String functionName, String argumentsFragment) {
        ChatFunctionCall function = new ChatFunctionCall();
        function.setName(functionName);
        function.setArguments(argumentsFragment);
        ChatToolCall toolCall = new ChatToolCall();
        toolCall.setIndex(index);
        toolCall.setId(id);
        toolCall.setType("function");
        toolCall.setFunction(function);
        return toolCall;
    }

    private ChatCompletionChunk createStopChunk() {
        return createStopChunk("stop");
    }

    private ChatCompletionChunk createStopChunk(String finishReason) {
        ChatCompletionChunk chunk = new ChatCompletionChunk();
        ChatCompletionChoice choice = new ChatCompletionChoice();
        choice.setFinishReason(finishReason);
        choice.setMessage(new ChatMessage());
        chunk.setChoices(Collections.singletonList(choice));
        return chunk;
    }

    private ChatCompletionChunk createUsageChunk(Usage usage) {
        ChatCompletionChunk chunk = new ChatCompletionChunk();
        chunk.setChoices(List.of());
        chunk.setUsage(usage);
        return chunk;
    }

    private void injectArkService(ArkLlm target)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = ArkLlm.class.getDeclaredField("arkService");
        field.setAccessible(true);
        field.set(target, arkService);
    }
}
