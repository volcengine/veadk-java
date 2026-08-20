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
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChunk;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionResult;
import com.volcengine.ark.runtime.model.completion.chat.ChatFunction;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.model.completion.chat.ChatTool;
import com.volcengine.ark.runtime.model.completion.chat.ChatToolCall;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.veadk.utils.EnvUtil;
import com.volcengine.veadk.utils.JSONUtil;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ark (Volcengine Ark Runtime) implementation of BaseLlm.
 *
 * <p>This adapter maps ADK's LlmRequest/LlmResponse to Ark Responses API semantics and supports
 * both streaming and aggregated (non-streaming) generation.
 */
public final class ArkLlm extends BaseLlm {

    private static final Logger log = LoggerFactory.getLogger(ArkLlm.class);

    // Role mapping from ADK roles to Ark roles
    private static final ImmutableMap<String, ChatMessageRole> ROLE_MAPPING =
            ImmutableMap.<String, ChatMessageRole>builder()
                    .put("user", ChatMessageRole.USER)
                    .put("model", ChatMessageRole.ASSISTANT)
                    .put("system", ChatMessageRole.SYSTEM)
                    .build();

    private final ArkService arkService;
    private final OpenAiCompatibleChatService compatibleChatService;
    private ChatCompletionRequest.ChatCompletionRequestThinking thinking = null;

    public ArkLlm(String modelName) {
        this(modelName, null);
    }

    public ArkLlm(String modelName, String thinking) {
        super(modelName);
        Objects.requireNonNull(modelName, "modelName must be set.");
        String apiBase = EnvUtil.getAgentApiBase();
        if (StringUtils.isNotBlank(apiBase)) {
            this.arkService = null;
            this.compatibleChatService =
                    new OpenAiCompatibleChatService(apiBase, EnvUtil.getAgentApiKey());
        } else {
            this.arkService = ArkService.builder().apiKey(EnvUtil.getAgentApiKey()).build();
            this.compatibleChatService = null;
        }
        if (StringUtils.isNotBlank(thinking)) {
            this.thinking = new ChatCompletionRequest.ChatCompletionRequestThinking(thinking);
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
            log.debug(
                    "Sending streaming generateContent request to model {}", arkRequest.getModel());
            // Handle streaming response
            return generateContentStreaming(arkRequest);
        } else {
            log.debug("Sending generateContent request to model {}", arkRequest.getModel());
            // Handle non-streaming response
            return Flowable.fromCallable(() -> createChatCompletion(arkRequest))
                    .map(this::toLlmResponse);
        }
    }

    private ChatCompletionResult createChatCompletion(ChatCompletionRequest arkRequest) {
        if (compatibleChatService != null) {
            return compatibleChatService.createChatCompletion(arkRequest);
        }
        return arkService.createChatCompletion(arkRequest);
    }

    /**
     * Handle streaming content generation
     * @param arkRequest The Ark completion request
     * @return Flowable of LlmResponse objects
     */
    private Flowable<LlmResponse> generateContentStreaming(ChatCompletionRequest arkRequest) {
        // Get streaming response from Ark service
        io.reactivex.Flowable<ChatCompletionChunk> streamResponse =
                compatibleChatService != null
                        ? compatibleChatService.streamChatCompletion(arkRequest)
                        : arkService.streamChatCompletion(arkRequest);

        return Flowable.defer(
                () -> {
                    // Accumulate complete text response
                    final StringBuilder accumulatedText = new StringBuilder();
                    // Buffer partial text for incremental responses
                    final StringBuilder partialText = new StringBuilder();
                    // Hold the last chunk for final processing
                    final ChatCompletionChunk[] lastChunkHolder = {null};
                    // Accumulate tool calls if any
                    final List<ChatToolCall> accumulatedToolCalls = new ArrayList<>();

                    return Flowable.fromPublisher(streamResponse)
                            .concatMap(
                                    chunk -> {
                                        lastChunkHolder[0] = chunk;
                                        log.debug("Raw Ark streaming chunk: {}", chunk);

                                        // Prepare list of responses to emit
                                        List<LlmResponse> responsesToEmit = new ArrayList<>();
                                        // Process text content from chunk
                                        processTextContent(
                                                chunk,
                                                accumulatedText,
                                                partialText,
                                                responsesToEmit);
                                        // Process tool calls from chunk
                                        processToolCalls(chunk, accumulatedToolCalls);

                                        // Handle stop chunk (final chunk)
                                        if (isStopChunk(chunk)) {
                                            processStopChunk(partialText, responsesToEmit);
                                        }

                                        // Emit responses if any
                                        if (responsesToEmit.isEmpty()) {
                                            return Flowable.empty();
                                        } else {
                                            log.debug("Responses to emit: {}", responsesToEmit);
                                            return Flowable.fromIterable(responsesToEmit);
                                        }
                                    })
                            .concatWith(
                                    Flowable.defer(
                                            () -> {
                                                // Process final response after stream ends
                                                ChatCompletionChunk lastChunk = lastChunkHolder[0];
                                                if (lastChunk == null
                                                        || accumulatedText.length() == 0) {
                                                    return Flowable.empty();
                                                }

                                                if (isStopChunk(lastChunk)) {
                                                    // Build and emit final aggregated response
                                                    return Flowable.just(
                                                            buildFinalResponse(
                                                                    accumulatedText.toString(),
                                                                    accumulatedToolCalls));
                                                }

                                                return Flowable.empty();
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
        String content = (String) chunk.getChoices().get(0).getMessage().getContent();

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
            ChatCompletionChunk chunk, List<ChatToolCall> accumulatedToolCalls) {
        // Extract tool calls from chunk
        List<ChatToolCall> toolCalls = chunk.getChoices().get(0).getMessage().getToolCalls();
        if (null != toolCalls && !toolCalls.isEmpty()) {
            ChatToolCall toolCall = toolCalls.get(0);
            // If tool call has ID, it's a new tool call
            if (StringUtils.isNotBlank(toolCall.getId())) {
                accumulatedToolCalls.add(toolCall);
            } else {
                // Otherwise, it's continuation of existing tool call
                int index = toolCall.getIndex();
                String arguments =
                        accumulatedToolCalls.get(index).getFunction().getArguments()
                                + toolCall.getFunction().getArguments();
                accumulatedToolCalls.get(index).getFunction().setArguments(arguments);
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
            String accumulatedText, List<ChatToolCall> accumulatedToolCalls) {
        List<Part> parts = new ArrayList<>();
        // Add text part
        parts.add(Part.fromText(accumulatedText));

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

        log.debug("LlmResponse:{}", response);
        return response;
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
        return llmRequest.contents().stream()
                .map(
                        content ->
                                ChatMessage.builder()
                                        .role(toArkRole(content.role().orElse("user")))
                                        .content(extractText(content))
                                        .build());
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

    /**
     * Extract text content from Content object
     * @param content The Content object
     * @return Extracted text
     */
    private String extractText(Content content) {
        StringBuilder textBuilder = new StringBuilder();
        // Use ifPresent with a lambda for a more functional and readable style
        content.parts()
                .ifPresent(
                        parts -> {
                            for (Part part : parts) {
                                if (part == null) {
                                    continue;
                                }
                                // Append text part
                                appendTextPart(part, textBuilder);
                                // Append function response part
                                appendFunctionResponsePart(part, textBuilder);
                            }
                        });
        return textBuilder.toString();
    }

    /**
     * Append text part to StringBuilder
     * @param part The Part object
     * @param textBuilder The StringBuilder to append to
     */
    private void appendTextPart(Part part, StringBuilder textBuilder) {
        // Append the text part directly to the main StringBuilder
        part.text().ifPresent(textBuilder::append);
    }

    /**
     * Append function response part to StringBuilder
     * @param part The Part object
     * @param textBuilder The StringBuilder to append to
     */
    private void appendFunctionResponsePart(Part part, StringBuilder textBuilder) {
        // Chain flatMap and ifPresent for safe, nested Optional handling
        part.functionResponse()
                .flatMap(FunctionResponse::response) // Safely get the inner Optional<Map>
                .ifPresent( // Execute only if the map is present
                        responseMap -> {
                            // Append the serialized JSON directly
                            textBuilder.append(JSONUtil.toJson(responseMap));
                        });
    }

    /**
     * Convert ADK role to Ark role
     * @param adkRole The ADK role string
     * @return Corresponding ChatMessageRole
     */
    private ChatMessageRole toArkRole(String adkRole) {
        ChatMessageRole role = ROLE_MAPPING.get(adkRole);
        if (role != null) {
            return role;
        }

        // TODO: need to handle tool calling in the future
        return ChatMessageRole.USER;
    }

    /**
     * Parse a single tool call into a Part
     * @param toolCall The ChatToolCall object
     * @return Part representing the function call
     * @throws JsonProcessingException If JSON parsing fails
     */
    private Part parseToolCallPart(ChatToolCall toolCall) throws JsonProcessingException {
        return Part.fromFunctionCall(
                toolCall.getFunction().getName(),
                JSONUtil.fromJson(
                        toolCall.getFunction().getArguments(),
                        new TypeReference<Map<String, Object>>() {}));
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
                        log.error("read function arguments error", e);
                    }
                });
        return parts;
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException("Ark LLM live connection is not supported.");
    }
}
