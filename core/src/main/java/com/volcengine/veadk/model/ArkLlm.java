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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.volcengine.ark.runtime.model.Usage;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChunk;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionResult;
import com.volcengine.ark.runtime.model.completion.chat.ChatFunction;
import com.volcengine.ark.runtime.model.completion.chat.ChatFunctionCall;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.model.completion.chat.ChatTool;
import com.volcengine.ark.runtime.model.completion.chat.ChatToolCall;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.veadk.utils.JSONUtil;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ark (Volcengine Ark Runtime) implementation of BaseLlm.
 *
 * <p>This adapter maps ADK's LlmRequest/LlmResponse to Ark Chat Completions semantics and supports
 * both streaming and aggregated (non-streaming) generation.
 */
public final class ArkLlm extends BaseLlm implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ArkLlm.class);

    private final ArkService arkService;
    private final Dispatcher dispatcher;
    private final ConnectionPool connectionPool;
    private final ArkLlmConfig config;
    private ChatCompletionRequest.ChatCompletionRequestThinking thinking = null;

    public ArkLlm(String modelName) {
        this(modelName, null);
    }

    public ArkLlm(String modelName, String thinking) {
        this(ArkLlmConfig.builder().modelName(modelName).thinking(thinking).build());
    }

    public ArkLlm(ArkLlmConfig config) {
        super(Objects.requireNonNull(config, "config must be set.").getModelName());
        this.config = config;
        this.dispatcher = new Dispatcher();
        this.connectionPool = new ConnectionPool(5, 1, TimeUnit.SECONDS);
        ArkService.Builder arkServiceBuilder =
                ArkService.builder()
                        .apiKey(config.getApiKey())
                        .dispatcher(dispatcher)
                        .connectionPool(connectionPool);
        if (StringUtils.isNotBlank(config.getApiBase())) {
            arkServiceBuilder.baseUrl(config.getApiBase());
        }
        this.arkService = arkServiceBuilder.build();
        if (StringUtils.isNotBlank(config.getThinking())) {
            this.thinking =
                    new ChatCompletionRequest.ChatCompletionRequestThinking(config.getThinking());
        }
    }

    public ArkLlmConfig config() {
        return config;
    }

    @Override
    public void close() {
        dispatcher.cancelAll();
        connectionPool.evictAll();
        arkService.shutdownExecutor();
        dispatcher.executorService().shutdownNow();
        try {
            dispatcher.executorService().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Generate content based on LLM request
     * @param llmRequest The request containing prompts and parameters
     * @param stream Whether to use streaming or not
     * @return Flowable of LlmResponse objects
     */
    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        // Convert ADK request to Ark request format
        ChatCompletionRequest arkRequest = toArkRequest(llmRequest);
        if (stream) {
            arkRequest.setStreamOptions(
                    ChatCompletionRequest.ChatCompletionRequestStreamOptions.of(true));
            log.debug(
                    "Sending streaming generateContent request to model {}", arkRequest.getModel());
            // Handle streaming response
            return generateContentStreaming(arkRequest);
        } else {
            log.debug("Sending generateContent request to model {}", arkRequest.getModel());
            // Handle non-streaming response
            return Flowable.fromCallable(() -> arkService.createChatCompletion(arkRequest))
                    .map(this::toLlmResponse);
        }
    }

    /**
     * Handle streaming content generation
     * @param arkRequest The Ark completion request
     * @return Flowable of LlmResponse objects
     */
    private Flowable<LlmResponse> generateContentStreaming(ChatCompletionRequest arkRequest) {
        return Flowable.defer(
                () -> {
                    StringBuilder accumulatedText = new StringBuilder();
                    StringBuilder partialText = new StringBuilder();
                    Map<Integer, ChatToolCall> toolCalls = new TreeMap<>();
                    Usage[] usage = {null};
                    boolean[] finished = {false};
                    return Flowable.fromPublisher(arkService.streamChatCompletion(arkRequest))
                            .concatMap(
                                    chunk -> {
                                        if (chunk.getUsage() != null) {
                                            // Usage chunks carry cumulative totals, not deltas.
                                            usage[0] = chunk.getUsage();
                                        }
                                        if (chunk.getChoices() == null
                                                || chunk.getChoices().isEmpty()) {
                                            return Flowable.<LlmResponse>empty();
                                        }
                                        List<LlmResponse> responses = new ArrayList<>();
                                        processTextContent(
                                                chunk, accumulatedText, partialText, responses);
                                        processToolCalls(chunk, toolCalls);
                                        if (isStopChunk(chunk)) {
                                            finished[0] = true;
                                            processStopChunk(partialText, responses);
                                        }
                                        return Flowable.fromIterable(responses);
                                    })
                            .concatWith(
                                    Flowable.defer(
                                            () -> {
                                                if (!finished[0]) {
                                                    return Flowable.error(
                                                            new IllegalStateException(
                                                                    "Ark stream ended without a"
                                                                            + " finish reason."));
                                                }
                                                return Flowable.just(
                                                        buildFinalResponse(
                                                                accumulatedText.toString(),
                                                                new ArrayList<>(toolCalls.values()),
                                                                usage[0]));
                                            }));
                });
    }

    /**
     * Process text content from a streaming chunk
     * @param chunk The streaming chunk
     * @param accumulatedText Accumulated complete text
     * @param partialText Partial text buffer
     * @param responsesToEmit List of responses to emit
     */
    private void processTextContent(
            ChatCompletionChunk chunk,
            StringBuilder accumulatedText,
            StringBuilder partialText,
            List<LlmResponse> responsesToEmit) {
        // Extract text content from chunk
        ChatMessage message = chunk.getChoices().get(0).getMessage();
        String content = message == null ? null : (String) message.getContent();

        if (StringUtils.isNotEmpty(content)) {
            // Add to accumulated text
            accumulatedText.append(content);
            // Add to partial text buffer
            partialText.append(content);
            // Emit partial response when buffer exceeds threshold
            if (partialText.length() > 30) {
                responsesToEmit.add(buildPartialResponse(partialText.toString()));
                partialText.setLength(0); // Clear buffer
            }
        }
    }

    /**
     * Build a partial response for streaming
     * @param text The text content
     * @return LlmResponse object representing partial response
     */
    private LlmResponse buildPartialResponse(String text) {
        return LlmResponse.builder()
                .content(Content.builder().role("model").parts(Part.fromText(text)).build())
                .partial(true) // Mark as partial response
                .build();
    }

    /**
     * Process tool calls from a streaming chunk
     * @param chunk The streaming chunk
     * @param accumulatedToolCalls List of accumulated tool calls
     */
    private void processToolCalls(
            ChatCompletionChunk chunk, Map<Integer, ChatToolCall> accumulatedToolCalls) {
        ChatMessage message = chunk.getChoices().get(0).getMessage();
        if (message == null || message.getToolCalls() == null) {
            return;
        }
        List<ChatToolCall> deltas = message.getToolCalls();
        for (int position = 0; position < deltas.size(); position++) {
            ChatToolCall delta = deltas.get(position);
            int index = delta.getIndex() == null ? position : delta.getIndex();
            ChatToolCall call =
                    accumulatedToolCalls.computeIfAbsent(
                            index,
                            ignored ->
                                    new ChatToolCall(
                                            null, "function", new ChatFunctionCall("", "")));
            if (StringUtils.isNotBlank(delta.getId())) {
                if (call.getId() != null && !call.getId().equals(delta.getId())) {
                    throw new IllegalArgumentException(
                            "Conflicting Ark tool call IDs for index " + index);
                }
                call.setId(delta.getId());
            }
            if (delta.getFunction() != null) {
                ChatFunctionCall function = call.getFunction();
                function.setName(
                        function.getName()
                                + StringUtils.defaultString(delta.getFunction().getName()));
                function.setArguments(
                        function.getArguments()
                                + StringUtils.defaultString(delta.getFunction().getArguments()));
            }
        }
    }

    /**
     * Process stop chunk (final chunk in stream)
     * @param partialText Partial text buffer
     * @param responsesToEmit List of responses to emit
     */
    private void processStopChunk(StringBuilder partialText, List<LlmResponse> responsesToEmit) {
        // Emit any remaining partial text
        if (!partialText.isEmpty()) {
            responsesToEmit.add(buildPartialResponse(partialText.toString()));
            partialText.setLength(0); // Clear buffer
        }
    }

    /**
     * Build final aggregated response
     * @param accumulatedText Complete text response
     * @param accumulatedToolCalls Tool calls if any
     * @return Final LlmResponse object
     */
    private LlmResponse buildFinalResponse(
            String accumulatedText, List<ChatToolCall> accumulatedToolCalls, Usage usage) {
        List<Part> parts = new ArrayList<>();
        // Add text part
        if (!accumulatedText.isEmpty()) {
            parts.add(Part.fromText(accumulatedText));
        }

        // Add tool call parts if any
        if (!accumulatedToolCalls.isEmpty()) {
            parts.addAll(parseToolCalls(accumulatedToolCalls));
        }

        // Build final response
        LlmResponse finalAggregatedResponse =
                LlmResponse.builder()
                        .content(Content.builder().role("model").parts(parts).build())
                        .partial(false) // Mark as complete response
                        .build();
        finalAggregatedResponse = withUsage(finalAggregatedResponse, usage);
        log.debug("finalAggregatedResponse to emit: {}", finalAggregatedResponse);
        return finalAggregatedResponse;
    }

    /**
     * Check if chunk is a stop chunk (final chunk)
     * @param chunk The streaming chunk
     * @return True if chunk is stop chunk, false otherwise
     */
    private boolean isStopChunk(ChatCompletionChunk chunk) {
        String finishReason = chunk.getChoices().get(0).getFinishReason();
        return StringUtils.isNotBlank(finishReason);
    }

    /**
     * Convert Ark response to ADK LlmResponse
     * @param arkResponse The Ark completion result
     * @return LlmResponse object
     */
    private LlmResponse toLlmResponse(ChatCompletionResult arkResponse) {
        log.debug("Raw Ark response:{}", arkResponse);
        LlmResponse response = null;

        // Check finish reason to determine response type
        String finishReason = arkResponse.getChoices().get(0).getFinishReason();
        if ("tool_calls".equalsIgnoreCase(finishReason)) {
            // Handle tool call response
            List<Part> parts = new ArrayList<>();

            // Add text content if any
            String text = (String) arkResponse.getChoices().get(0).getMessage().getContent();
            if (StringUtils.isNotEmpty(text)) {
                parts.add(Part.fromText(text));
            }

            // Add tool call parts
            parts.addAll(
                    parseToolCalls(arkResponse.getChoices().get(0).getMessage().getToolCalls()));

            response =
                    LlmResponse.builder()
                            .content(Content.builder().role("model").parts(parts).build())
                            .build();
        } else {
            // Handle regular text response
            String text = (String) arkResponse.getChoices().get(0).getMessage().getContent();
            response =
                    LlmResponse.builder()
                            .content(
                                    Content.builder()
                                            .role("model")
                                            .parts(Part.fromText(text))
                                            .build())
                            .build();
        }

        response = withUsage(response, arkResponse.getUsage());
        log.debug("LlmResponse:{}", response);
        return response;
    }

    private LlmResponse withUsage(LlmResponse response, Usage usage) {
        if (usage == null) {
            return response;
        }
        GenerateContentResponseUsageMetadata metadata =
                GenerateContentResponseUsageMetadata.builder()
                        .promptTokenCount(Math.toIntExact(usage.getPromptTokens()))
                        .candidatesTokenCount(Math.toIntExact(usage.getCompletionTokens()))
                        .totalTokenCount(Math.toIntExact(usage.getTotalTokens()))
                        .build();
        return response.toBuilder().usageMetadata(metadata).build();
    }

    /**
     * Convert ADK LlmRequest to Ark ChatCompletionRequest
     * @param llmRequest The ADK request
     * @return ChatCompletionRequest object for Ark API
     */
    private ChatCompletionRequest toArkRequest(LlmRequest llmRequest) {
        // Determine model name to use
        String effectiveModelName = llmRequest.model().orElse(model());

        // Build chat messages from request
        List<ChatMessage> messages = buildChatMessages(llmRequest);

        // Create base request
        ChatCompletionRequest request =
                ChatCompletionRequest.builder()
                        .model(effectiveModelName)
                        .messages(messages)
                        .build();

        llmRequest
                .config()
                .ifPresent(
                        generation -> {
                            generation
                                    .temperature()
                                    .ifPresent(
                                            value -> request.setTemperature(value.doubleValue()));
                            generation
                                    .topP()
                                    .ifPresent(value -> request.setTopP(value.doubleValue()));
                            generation.maxOutputTokens().ifPresent(request::setMaxTokens);
                        });

        // Add thinking parameter if set
        if (null != thinking) {
            request.setThinking(thinking);
        }

        // Add tools if any
        if (llmRequest.tools() != null && !llmRequest.tools().isEmpty()) {
            List<ChatTool> chatTools = buildChatTools(llmRequest);
            if (!chatTools.isEmpty()) {
                request.setTools(chatTools);
            }
        }

        return request;
    }

    /**
     * Build chat messages from LlmRequest
     * @param llmRequest The ADK request
     * @return List of ChatMessage objects
     */
    private List<ChatMessage> buildChatMessages(LlmRequest llmRequest) {
        // Build system messages
        Stream<ChatMessage> systemMessages = buildSystemMessages(llmRequest);
        // Build content messages
        Stream<ChatMessage> contentMessages = buildContentMessages(llmRequest);

        // Combine system and content messages
        return Stream.concat(systemMessages, contentMessages).collect(Collectors.toList());
    }

    /**
     * Build system messages from LlmRequest
     * @param llmRequest The ADK request
     * @return Stream of ChatMessage objects with system role
     */
    private Stream<ChatMessage> buildSystemMessages(LlmRequest llmRequest) {
        return llmRequest.getSystemInstructions().stream()
                .map(
                        instruction ->
                                ChatMessage.builder()
                                        .role(ChatMessageRole.SYSTEM)
                                        .content(instruction)
                                        .build());
    }

    /**
     * Build content messages from LlmRequest
     * @param llmRequest The ADK request
     * @return Stream of ChatMessage objects with user/model roles
     */
    private Stream<ChatMessage> buildContentMessages(LlmRequest llmRequest) {
        return new ArkChatHistoryMapper().map(llmRequest.contents()).stream();
    }

    /**
     * Build chat tools from LlmRequest
     * @param llmRequest The ADK request
     * @return List of ChatTool objects
     */
    private List<ChatTool> buildChatTools(LlmRequest llmRequest) {
        return llmRequest.tools().values().stream()
                .map(this::convertToChatTool)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    /**
     * Convert ADK BaseTool to Ark ChatTool
     * @param tool The ADK tool
     * @return Optional ChatTool object
     */
    private Optional<ChatTool> convertToChatTool(BaseTool tool) {
        // Get tool parameters schema
        Optional<Schema> parameters = tool.declaration().flatMap(FunctionDeclaration::parameters);
        return parameters.map(
                schema -> {
                    // Convert schema to map
                    Map<String, Object> schemaMap =
                            JSONUtil.convertValue(
                                    schema, new TypeReference<Map<String, Object>>() {});

                    // Normalize type strings in schema
                    updateTypeString(schemaMap);

                    // Create chat function
                    ChatFunction chatFunction = new ChatFunction();
                    chatFunction.setName(tool.name());
                    chatFunction.setDescription(tool.description());
                    chatFunction.setParameters(JSONUtil.valueToTree(schemaMap));

                    // Return chat tool
                    return new ChatTool("function", chatFunction);
                });
    }

    /**
     * Normalize type strings in schema map
     * @param valueDict The schema map
     */
    private void updateTypeString(Map<String, Object> valueDict) {
        if (valueDict == null) {
            return;
        }

        // 1. Process "type" at the current level.
        if (valueDict.get("type") instanceof String) {
            String typeValue = (String) valueDict.get("type");
            valueDict.put("type", typeValue.toLowerCase());
        }

        // 2. Recurse into "properties".
        Object propertiesValue = valueDict.get("properties");
        if (propertiesValue instanceof Map) {
            for (Object value : ((Map<?, ?>) propertiesValue).values()) {
                if (value instanceof Map) {
                    //noinspection unchecked
                    updateTypeString((Map<String, Object>) value);
                }
            }
        }

        // 3. Recurse into "items". The recursive call will handle any nested "properties".
        Object itemsValue = valueDict.get("items");
        if (itemsValue instanceof Map) {
            //noinspection unchecked
            updateTypeString((Map<String, Object>) itemsValue);
        }
    }

    private Part parseToolCallPart(ChatToolCall toolCall) throws JsonProcessingException {
        if (StringUtils.isBlank(toolCall.getId())
                || toolCall.getFunction() == null
                || StringUtils.isBlank(toolCall.getFunction().getName())) {
            throw new IllegalArgumentException(
                    "Ark tool call must include an ID and function name.");
        }
        Map<String, Object> arguments =
                JSONUtil.fromJson(
                        StringUtils.defaultIfBlank(toolCall.getFunction().getArguments(), "{}"),
                        new TypeReference<Map<String, Object>>() {});
        if (arguments == null) {
            throw new IllegalArgumentException("Ark tool arguments must be a JSON object.");
        }
        return Part.builder()
                .functionCall(
                        FunctionCall.builder()
                                .id(toolCall.getId())
                                .name(toolCall.getFunction().getName())
                                .args(arguments)
                                .build())
                .build();
    }

    /**
     * Parse multiple tool calls into Parts
     * @param toolCalls List of ChatToolCall objects
     * @return List of Part objects representing function calls
     */
    private List<Part> parseToolCalls(List<ChatToolCall> toolCalls) {
        List<Part> parts = new ArrayList<>();
        toolCalls.forEach(
                toolCall -> {
                    try {
                        parts.add(parseToolCallPart(toolCall));
                    } catch (JsonProcessingException e) {
                        throw new IllegalArgumentException(
                                "Invalid JSON arguments in Ark tool call.", e);
                    }
                });
        return parts;
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException("Ark LLM live connection is not supported.");
    }
}
