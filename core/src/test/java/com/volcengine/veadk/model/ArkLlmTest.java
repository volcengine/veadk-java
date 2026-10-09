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
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChoice;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChunk;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionResult;
import com.volcengine.ark.runtime.model.completion.chat.ChatFunctionCall;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatToolCall;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.veadk.utils.EnvUtil;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junitpioneer.jupiter.ClearEnvironmentVariable;
import org.junitpioneer.jupiter.SetEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArkLlmTest {

    @Mock private ArkService arkService;

    private ArkLlm arkLlm;

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        try (MockedStatic<EnvUtil> mocked = mockStatic(EnvUtil.class)) {
            mocked.when(EnvUtil::getOptionalAgentApiKey).thenReturn("test-api-key");
            arkLlm = new ArkLlm("test-model");
        }
        Field field = ArkLlm.class.getDeclaredField("arkService");
        field.setAccessible(true);
        field.set(arkLlm, arkService);
    }

    @Test
    @SetEnvironmentVariable(key = "MODEL_AGENT_API_KEY", value = "env-api-key")
    void constructorUsesEnvApiKeyWhenExplicitKeyIsAbsent() {
        ArkLlm llm = new ArkLlm("env-model");

        assertEquals("env-model", llm.config().getModelName());
        assertEquals("env-api-key", llm.config().getApiKey());
    }

    @Test
    @SetEnvironmentVariable(key = "MODEL_AGENT_API_KEY", value = "env-api-key")
    void explicitApiKeyOverridesEnvApiKey() {
        ArkLlm llm =
                new ArkLlm(
                        ArkLlmConfig.builder()
                                .modelName("explicit-model")
                                .apiKey("explicit-api-key")
                                .build());

        assertEquals("explicit-api-key", llm.config().getApiKey());
    }

    @Test
    void missingEnvApiKeyFallsBackToArkOpenApiResolver() {
        try (MockedStatic<EnvUtil> env = mockStatic(EnvUtil.class);
                MockedConstruction<ArkApiKeyResolver> resolver =
                        Mockito.mockConstruction(
                                ArkApiKeyResolver.class,
                                (mock, context) ->
                                        when(mock.resolve("named-key"))
                                                .thenReturn("resolved-key"))) {
            env.when(EnvUtil::getOptionalAgentApiKey).thenReturn("");
            env.when(EnvUtil::getAccessKey).thenReturn("ak");
            env.when(EnvUtil::getSecretKey).thenReturn("sk");
            env.when(EnvUtil::getSessionToken).thenReturn("token");
            env.when(EnvUtil::getRegion).thenReturn("cn-beijing");
            env.when(EnvUtil::getCloudProvider).thenReturn("");
            env.when(EnvUtil::getAgentApiKeyName).thenReturn("named-key");

            ArkLlmConfig config = ArkLlmConfig.builder().modelName("fallback-model").build();

            assertEquals("resolved-key", config.getApiKey());
            assertEquals(1, resolver.constructed().size());
        }
    }

    @Test
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_KEY")
    @ClearEnvironmentVariable(key = "VOLCENGINE_ACCESS_KEY")
    @ClearEnvironmentVariable(key = "VOLCENGINE_SECRET_KEY")
    void missingApiKeyFailsWithClearMessage() {
        IllegalStateException exception =
                Assertions.assertThrows(
                        IllegalStateException.class,
                        () -> ArkLlmConfig.builder().modelName("missing-key-model").build());

        assertTrue(exception.getMessage().contains("VOLCENGINE_ACCESS_KEY"));
    }

    @Test
    @SetEnvironmentVariable(key = "MODEL_AGENT_API_KEY", value = "env-api-key")
    void thinkingFromLegacyConstructorIsAppliedToArkRequest() throws Exception {
        ArkLlm llm = new ArkLlm("thinking-model", "enabled");
        injectArkService(llm);
        when(arkService.createChatCompletion(any(ChatCompletionRequest.class)))
                .thenReturn(createMockTextResult("thinking ok"));

        llm.generateContent(simpleRequest("thinking-model", "Hello"), false).blockingFirst();

        ArgumentCaptor<ChatCompletionRequest> requestCaptor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        org.mockito.Mockito.verify(arkService).createChatCompletion(requestCaptor.capture());
        assertEquals("enabled", requestCaptor.getValue().getThinking().getType());
    }

    @Test
    void generateContent_nonStreaming_textResponse() throws InterruptedException {
        LlmRequest llmRequest = simpleRequest("test-model", "Hello");

        ChatCompletionResult mockResult = createMockTextResult("Hi there!");
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
    }

    @Test
    void generateContent_nonStreaming_toolCallResponse() throws InterruptedException {
        LlmRequest llmRequest = simpleRequest("test-model", "Search for cats");

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
        assertTrue(fc.args().isPresent());
        assertEquals(Map.of("query", "cats"), fc.args().get());
    }

    @Test
    void generateContent_streaming_textResponse() throws InterruptedException {
        LlmRequest llmRequest = simpleRequest("test-model", "Hello");

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

    private LlmRequest simpleRequest(String modelName, String content) {
        return LlmRequest.builder()
                .model(modelName)
                .contents(
                        Collections.singletonList(
                                Content.builder()
                                        .role("user")
                                        .parts(Part.fromText(content))
                                        .build()))
                .build();
    }

    private void injectArkService(ArkLlm llm) throws NoSuchFieldException, IllegalAccessException {
        Field field = ArkLlm.class.getDeclaredField("arkService");
        field.setAccessible(true);
        field.set(llm, arkService);
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

    private ChatCompletionChunk createStopChunk() {
        ChatCompletionChunk chunk = new ChatCompletionChunk();
        ChatCompletionChoice choice = new ChatCompletionChoice();
        choice.setFinishReason("stop");
        choice.setMessage(new ChatMessage());
        chunk.setChoices(Collections.singletonList(choice));
        return chunk;
    }
}
