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
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.VideoMetadata;
import com.volcengine.ark.runtime.model.Usage;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChunk;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionContentPart;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionResult;
import com.volcengine.ark.runtime.model.completion.chat.ChatFunction;
import com.volcengine.ark.runtime.model.completion.chat.ChatFunctionCall;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.model.completion.chat.ChatTool;
import com.volcengine.ark.runtime.model.completion.chat.ChatToolCall;
import com.volcengine.ark.runtime.model.completion.chat.ResponseFormatJSONSchemaJSONSchemaParam;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.veadk.utils.EnvUtil;
import com.volcengine.veadk.utils.JSONUtil;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final List<String> fallbacks;
    private ChatCompletionRequest.ChatCompletionRequestThinking thinking = null;

    public ArkLlm(String modelName) {
        this(modelName, null);
    }

    public ArkLlm(String modelName, String thinking) {
        this(modelName, thinking, EnvUtil.getAgentApiKey(), null);
    }

    public ArkLlm(String modelName, String apiKey, String apiBase) {
        this(modelName, null, apiKey, apiBase);
    }

    public ArkLlm(String modelName, String thinking, String apiKey, String apiBase) {
        this(modelName, thinking, apiKey, apiBase, List.of());
    }

    public ArkLlm(List<String> modelNames) {
        this(modelNames, null);
    }

    public ArkLlm(List<String> modelNames, String thinking) {
        this(primaryModelName(modelNames), thinking, EnvUtil.getAgentApiKey(), null, modelNames);
    }

    public ArkLlm(List<String> modelNames, String apiKey, String apiBase) {
        this(primaryModelName(modelNames), null, apiKey, apiBase, modelNames);
    }

    public ArkLlm(List<String> modelNames, String thinking, String apiKey, String apiBase) {
        this(primaryModelName(modelNames), thinking, apiKey, apiBase, modelNames);
    }

    private ArkLlm(
            String modelName, String thinking, String apiKey, String apiBase, List<String> models) {
        super(modelName);
        requireModelName(modelName);
        this.fallbacks = fallbackModelNames(models);
        ArkService.Builder serviceBuilder =
                ArkService.builder().apiKey(Objects.requireNonNull(apiKey, "apiKey must be set."));
        if (StringUtils.isNotBlank(apiBase)) {
            serviceBuilder.baseUrl(apiBase);
        }
        this.arkService = serviceBuilder.build();
        if (StringUtils.isNotBlank(thinking)) {
            this.thinking = new ChatCompletionRequest.ChatCompletionRequestThinking(thinking);
        }
    }

    public List<String> fallbacks() {
        return fallbacks;
    }

    private static String primaryModelName(List<String> modelNames) {
        Objects.requireNonNull(modelNames, "modelNames must be set.");
        if (modelNames.isEmpty()) {
            throw new IllegalArgumentException("modelNames must not be empty");
        }
        return requireModelName(modelNames.get(0));
    }

    private static List<String> fallbackModelNames(List<String> modelNames) {
        if (modelNames == null || modelNames.size() <= 1) {
            return List.of();
        }
        return modelNames.subList(1, modelNames.size()).stream()
                .map(ArkLlm::requireModelName)
                .toList();
    }

    private static String requireModelName(String modelName) {
        Objects.requireNonNull(modelName, "modelName must be set.");
        if (modelName.isBlank()) {
            throw new IllegalArgumentException("modelName must not be blank");
        }
        return modelName;
    }

    private List<String> modelCandidates(LlmRequest llmRequest) {
        Optional<String> requestedModel = llmRequest.model().filter(StringUtils::isNotBlank);
        // ADK's Basic request processor always copies BaseLlm.model() into the request. Treat that
        // value as the configured primary model so fallbacks remain available in normal Agent runs.
        if (requestedModel.isPresent() && !requestedModel.get().equals(model())) {
            return List.of(requestedModel.get());
        }
        return Stream.concat(Stream.of(model()), fallbacks.stream()).toList();
    }

    /**
     * Generate content based on LLM request
     * @param llmRequest The request containing prompts and parameters
     * @param stream Whether to use streaming or not
     * @return Flowable of LlmResponse objects
     */
    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        return Flowable.defer(
                () ->
                        generateContentWithFallbacks(
                                llmRequest, stream, modelCandidates(llmRequest), 0));
    }

    private Flowable<LlmResponse> generateContentWithFallbacks(
            LlmRequest llmRequest, boolean stream, List<String> modelCandidates, int index) {
        String modelName = modelCandidates.get(index);
        ChatCompletionRequest arkRequest = toArkRequest(llmRequest, modelName);
        Flowable<LlmResponse> attempt =
                Flowable.defer(() -> generateContentWithModel(arkRequest, stream));
        AtomicBoolean emittedResponse = new AtomicBoolean(false);
        return attempt.doOnNext(response -> emittedResponse.set(true))
                .onErrorResumeNext(
                        error -> {
                            if (emittedResponse.get() || index + 1 >= modelCandidates.size()) {
                                return Flowable.error(error);
                            }
                            String nextModel = modelCandidates.get(index + 1);
                            log.warn(
                                    "Ark request with model {} failed before emitting a response;"
                                            + " falling back to {}",
                                    modelName,
                                    nextModel,
                                    error);
                            return generateContentWithFallbacks(
                                    llmRequest, stream, modelCandidates, index + 1);
                        });
    }

    private Flowable<LlmResponse> generateContentWithModel(
            ChatCompletionRequest arkRequest, boolean stream) {
        if (stream) {
            log.debug(
                    "Sending streaming generateContent request to model {}", arkRequest.getModel());
            arkRequest.setStreamOptions(
                    ChatCompletionRequest.ChatCompletionRequestStreamOptions.of(true));
            return generateContentStreaming(arkRequest);
        }
        log.debug("Sending generateContent request to model {}", arkRequest.getModel());
        return Flowable.fromCallable(() -> arkService.createChatCompletion(arkRequest))
                .map(this::toLlmResponse);
    }

    /**
     * Handle streaming content generation
     * @param arkRequest The Ark completion request
     * @return Flowable of LlmResponse objects
     */
    private Flowable<LlmResponse> generateContentStreaming(ChatCompletionRequest arkRequest) {
        io.reactivex.Flowable<ChatCompletionChunk> streamResponse =
                arkService.streamChatCompletion(arkRequest);

        return Flowable.defer(
                () -> {
                    final StringBuilder accumulatedText = new StringBuilder();
                    final StringBuilder partialText = new StringBuilder();
                    final Map<Integer, ChatToolCall> accumulatedToolCalls = new TreeMap<>();
                    final String[] finishReason = {null};
                    final Usage[] usage = {null};

                    return Flowable.fromPublisher(streamResponse)
                            .concatMap(
                                    chunk -> {
                                        log.debug("Raw Ark streaming chunk: {}", chunk);
                                        if (chunk.getUsage() != null) {
                                            usage[0] = chunk.getUsage();
                                        }
                                        String chunkFinishReason = finishReason(chunk);
                                        if (StringUtils.isNotBlank(chunkFinishReason)) {
                                            finishReason[0] = chunkFinishReason;
                                        }

                                        List<LlmResponse> responsesToEmit = new ArrayList<>();
                                        processTextContent(
                                                chunk,
                                                accumulatedText,
                                                partialText,
                                                responsesToEmit);
                                        processToolCalls(chunk, accumulatedToolCalls);

                                        if (StringUtils.isNotBlank(chunkFinishReason)) {
                                            processStopChunk(partialText, responsesToEmit);
                                        }

                                        if (responsesToEmit.isEmpty()) {
                                            return Flowable.empty();
                                        }
                                        log.debug("Responses to emit: {}", responsesToEmit);
                                        return Flowable.fromIterable(responsesToEmit);
                                    })
                            .concatWith(
                                    Flowable.defer(
                                            () -> {
                                                if (StringUtils.isBlank(finishReason[0])
                                                        || (accumulatedText.isEmpty()
                                                                && accumulatedToolCalls
                                                                        .isEmpty())) {
                                                    return Flowable.empty();
                                                }
                                                return Flowable.just(
                                                        buildFinalResponse(
                                                                accumulatedText.toString(),
                                                                new ArrayList<>(
                                                                        accumulatedToolCalls
                                                                                .values()),
                                                                finishReason[0],
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
        if (!hasMessageChoice(chunk)) {
            return;
        }
        Object rawContent = chunk.getChoices().get(0).getMessage().getContent();
        String content = rawContent instanceof String ? (String) rawContent : null;

        if (StringUtils.isNotEmpty(content)) {
            accumulatedText.append(content);
            partialText.append(content);
            if (partialText.length() > 30) {
                responsesToEmit.add(buildPartialResponse(partialText.toString()));
                partialText.setLength(0);
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
        if (!hasMessageChoice(chunk)) {
            return;
        }
        List<ChatToolCall> toolCalls = chunk.getChoices().get(0).getMessage().getToolCalls();
        if (toolCalls == null) {
            return;
        }
        for (ChatToolCall toolCall : toolCalls) {
            if (toolCall == null) {
                continue;
            }
            int index = resolveToolCallIndex(toolCall, accumulatedToolCalls);
            ChatToolCall accumulated =
                    accumulatedToolCalls.computeIfAbsent(index, unused -> new ChatToolCall());
            mergeToolCall(accumulated, toolCall);
        }
    }

    private int resolveToolCallIndex(
            ChatToolCall toolCall, Map<Integer, ChatToolCall> accumulatedToolCalls) {
        if (toolCall.getIndex() != null && toolCall.getIndex() >= 0) {
            return toolCall.getIndex();
        }
        if (StringUtils.isNotBlank(toolCall.getId())) {
            return accumulatedToolCalls.entrySet().stream()
                    .filter(entry -> toolCall.getId().equals(entry.getValue().getId()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseGet(() -> nextToolCallIndex(accumulatedToolCalls));
        }
        return accumulatedToolCalls.size() == 1
                ? accumulatedToolCalls.keySet().iterator().next()
                : nextToolCallIndex(accumulatedToolCalls);
    }

    private int nextToolCallIndex(Map<Integer, ChatToolCall> accumulatedToolCalls) {
        int index = 0;
        while (accumulatedToolCalls.containsKey(index)) {
            index++;
        }
        return index;
    }

    private void mergeToolCall(ChatToolCall target, ChatToolCall fragment) {
        if (StringUtils.isNotBlank(fragment.getId())) {
            target.setId(fragment.getId());
        }
        if (StringUtils.isNotBlank(fragment.getType())) {
            target.setType(fragment.getType());
        }
        ChatFunctionCall fragmentFunction = fragment.getFunction();
        if (fragmentFunction == null) {
            return;
        }
        ChatFunctionCall targetFunction = target.getFunction();
        if (targetFunction == null) {
            targetFunction = new ChatFunctionCall();
            target.setFunction(targetFunction);
        }
        if (StringUtils.isNotBlank(fragmentFunction.getName())) {
            targetFunction.setName(fragmentFunction.getName());
        }
        if (fragmentFunction.getArguments() != null) {
            targetFunction.setArguments(
                    Objects.requireNonNullElse(targetFunction.getArguments(), "")
                            + fragmentFunction.getArguments());
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
            partialText.setLength(0);
        }
    }

    /**
     * Build final aggregated response
     * @param accumulatedText Complete text response
     * @param accumulatedToolCalls Tool calls if any
     * @return Final LlmResponse object
     */
    private LlmResponse buildFinalResponse(
            String accumulatedText,
            List<ChatToolCall> accumulatedToolCalls,
            String finishReason,
            Usage usage) {
        List<Part> parts = new ArrayList<>();
        if (StringUtils.isNotEmpty(accumulatedText)) {
            parts.add(Part.fromText(accumulatedText));
        }

        if (!accumulatedToolCalls.isEmpty()) {
            parts.addAll(parseToolCalls(accumulatedToolCalls));
        }

        LlmResponse.Builder builder =
                LlmResponse.builder()
                        .content(Content.builder().role("model").parts(parts).build())
                        .partial(false);
        toFinishReason(finishReason).ifPresent(builder::finishReason);
        toUsageMetadata(usage).ifPresent(builder::usageMetadata);
        LlmResponse finalAggregatedResponse = builder.build();
        log.debug("finalAggregatedResponse to emit: {}", finalAggregatedResponse);
        return finalAggregatedResponse;
    }

    /**
     * Check if chunk is a stop chunk (final chunk)
     * @param chunk The streaming chunk
     * @return True if chunk is stop chunk, false otherwise
     */
    private String finishReason(ChatCompletionChunk chunk) {
        return chunk.getChoices() == null || chunk.getChoices().isEmpty()
                ? null
                : chunk.getChoices().get(0).getFinishReason();
    }

    private boolean hasMessageChoice(ChatCompletionChunk chunk) {
        return chunk.getChoices() != null
                && !chunk.getChoices().isEmpty()
                && chunk.getChoices().get(0).getMessage() != null;
    }

    /**
     * Convert Ark response to ADK LlmResponse
     * @param arkResponse The Ark completion result
     * @return LlmResponse object
     */
    private LlmResponse toLlmResponse(ChatCompletionResult arkResponse) {
        log.debug("Raw Ark response:{}", arkResponse);

        // Check finish reason to determine response type
        String finishReason = arkResponse.getChoices().get(0).getFinishReason();
        List<Part> parts = new ArrayList<>();
        String text = (String) arkResponse.getChoices().get(0).getMessage().getContent();
        if (StringUtils.isNotEmpty(text)) {
            parts.add(Part.fromText(text));
        }
        if ("tool_calls".equalsIgnoreCase(finishReason)) {
            // Add tool call parts
            parts.addAll(
                    parseToolCalls(arkResponse.getChoices().get(0).getMessage().getToolCalls()));
        }

        LlmResponse response = buildLlmResponse(parts, finishReason, arkResponse.getUsage());
        log.debug("LlmResponse:{}", response);
        return response;
    }

    private LlmResponse buildLlmResponse(String text, String finishReason, Usage usage) {
        List<Part> parts = new ArrayList<>();
        if (StringUtils.isNotEmpty(text)) {
            parts.add(Part.fromText(text));
        }
        return buildLlmResponse(parts, finishReason, usage);
    }

    private LlmResponse buildLlmResponse(List<Part> parts, String finishReason, Usage usage) {
        LlmResponse.Builder builder =
                LlmResponse.builder().content(Content.builder().role("model").parts(parts).build());
        toFinishReason(finishReason).ifPresent(builder::finishReason);
        toUsageMetadata(usage).ifPresent(builder::usageMetadata);
        return builder.build();
    }

    private Optional<FinishReason> toFinishReason(String finishReason) {
        if (StringUtils.isBlank(finishReason)) {
            return Optional.empty();
        }
        FinishReason.Known known =
                switch (finishReason.toLowerCase()) {
                    case "stop", "tool_calls", "function_call" -> FinishReason.Known.STOP;
                    case "length" -> FinishReason.Known.MAX_TOKENS;
                    case "content_filter" -> FinishReason.Known.SAFETY;
                    default -> FinishReason.Known.OTHER;
                };
        return Optional.of(new FinishReason(known));
    }

    private Optional<GenerateContentResponseUsageMetadata> toUsageMetadata(Usage usage) {
        if (usage == null) {
            return Optional.empty();
        }
        GenerateContentResponseUsageMetadata.Builder builder =
                GenerateContentResponseUsageMetadata.builder()
                        .promptTokenCount(toIntTokenCount(usage.getPromptTokens()))
                        .candidatesTokenCount(toIntTokenCount(usage.getCompletionTokens()))
                        .totalTokenCount(toIntTokenCount(usage.getTotalTokens()));
        if (usage.getPromptTokensDetails() != null
                && usage.getPromptTokensDetails().getCachedTokens() != null) {
            builder.cachedContentTokenCount(usage.getPromptTokensDetails().getCachedTokens());
        }
        if (usage.getCompletionTokensDetails() != null
                && usage.getCompletionTokensDetails().getReasoningTokens() != null) {
            builder.thoughtsTokenCount(usage.getCompletionTokensDetails().getReasoningTokens());
        }
        return Optional.of(builder.build());
    }

    private Integer toIntTokenCount(long value) {
        return Math.toIntExact(Math.min(value, Integer.MAX_VALUE));
    }

    /**
     * Convert ADK LlmRequest to Ark ChatCompletionRequest
     * @param llmRequest The ADK request
     * @return ChatCompletionRequest object for Ark API
     */
    private ChatCompletionRequest toArkRequest(LlmRequest llmRequest) {
        return toArkRequest(llmRequest, llmRequest.model().orElse(model()));
    }

    private ChatCompletionRequest toArkRequest(LlmRequest llmRequest, String effectiveModelName) {
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

        llmRequest.config().ifPresent(config -> applyGenerateContentConfig(request, config));

        // Add tools if any
        if (llmRequest.tools() != null && !llmRequest.tools().isEmpty()) {
            List<ChatTool> chatTools = buildChatTools(llmRequest);
            if (!chatTools.isEmpty()) {
                request.setTools(chatTools);
            }
        }

        return request;
    }

    private void applyGenerateContentConfig(
            ChatCompletionRequest request, GenerateContentConfig config) {
        config.temperature().map(Float::doubleValue).ifPresent(request::setTemperature);
        config.topP().map(Float::doubleValue).ifPresent(request::setTopP);
        config.maxOutputTokens().ifPresent(request::setMaxTokens);
        config.stopSequences().filter(stop -> !stop.isEmpty()).ifPresent(request::setStop);
        config.presencePenalty().map(Float::doubleValue).ifPresent(request::setPresencePenalty);
        config.frequencyPenalty().map(Float::doubleValue).ifPresent(request::setFrequencyPenalty);
        config.candidateCount().ifPresent(request::setN);
        config.responseLogprobs().ifPresent(request::setLogprobs);
        config.logprobs()
                .ifPresent(
                        topLogprobs -> {
                            request.setLogprobs(true);
                            request.setTopLogprobs(topLogprobs);
                        });
        config.responseSchema()
                .ifPresentOrElse(
                        schema -> request.setResponseFormat(buildJsonSchemaResponseFormat(schema)),
                        () ->
                                config.responseJsonSchema()
                                        .ifPresentOrElse(
                                                schema ->
                                                        request.setResponseFormat(
                                                                buildJsonSchemaResponseFormat(
                                                                        schema)),
                                                () ->
                                                        applyJsonObjectResponseFormat(
                                                                request, config)));
    }

    private ChatCompletionRequest.ChatCompletionRequestResponseFormat buildJsonSchemaResponseFormat(
            Schema schema) {
        Map<String, Object> schemaMap =
                JSONUtil.convertValue(schema, new TypeReference<Map<String, Object>>() {});
        updateTypeString(schemaMap);
        ResponseFormatJSONSchemaJSONSchemaParam jsonSchema =
                new ResponseFormatJSONSchemaJSONSchemaParam(
                        schema.title().filter(StringUtils::isNotBlank).orElse("response_schema"),
                        schema.description().orElse(null),
                        JSONUtil.valueToTree(schemaMap),
                        true);
        return new ChatCompletionRequest.ChatCompletionRequestResponseFormat(
                "json_schema", jsonSchema);
    }

    private ChatCompletionRequest.ChatCompletionRequestResponseFormat buildJsonSchemaResponseFormat(
            Object schema) {
        ResponseFormatJSONSchemaJSONSchemaParam jsonSchema =
                new ResponseFormatJSONSchemaJSONSchemaParam(
                        "response_schema", null, JSONUtil.valueToTree(schema), true);
        return new ChatCompletionRequest.ChatCompletionRequestResponseFormat(
                "json_schema", jsonSchema);
    }

    private void applyJsonObjectResponseFormat(
            ChatCompletionRequest request, GenerateContentConfig config) {
        config.responseMimeType()
                .filter(mimeType -> "application/json".equalsIgnoreCase(mimeType))
                .ifPresent(
                        unused ->
                                request.setResponseFormat(
                                        new ChatCompletionRequest
                                                .ChatCompletionRequestResponseFormat(
                                                "json_object")));
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
                .flatMap(content -> buildContentMessages(content).stream());
    }

    private List<ChatMessage> buildContentMessages(Content content) {
        if (hasFunctionResponsePart(content)) {
            return buildFunctionResponseMessages(content);
        }
        if (hasFunctionCallPart(content)) {
            return List.of(buildFunctionCallMessage(content));
        }
        return List.of(buildContentMessage(content));
    }

    private ChatMessage buildContentMessage(Content content) {
        ChatMessage.Builder builder =
                ChatMessage.builder().role(toArkRole(content.role().orElse("user")));
        if (hasSupportedMediaPart(content)) {
            return builder.multiContent(extractContentParts(content)).build();
        }
        return builder.content(extractText(content)).build();
    }

    private boolean hasFunctionResponsePart(Content content) {
        return content.parts().stream()
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .anyMatch(part -> part.functionResponse().isPresent());
    }

    private boolean hasFunctionCallPart(Content content) {
        return content.parts().stream()
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .anyMatch(part -> part.functionCall().isPresent());
    }

    private List<ChatMessage> buildFunctionResponseMessages(Content content) {
        return content.parts().stream()
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .flatMap(part -> part.functionResponse().stream())
                .map(this::buildFunctionResponseMessage)
                .toList();
    }

    private ChatMessage buildFunctionResponseMessage(FunctionResponse functionResponse) {
        ChatMessage.Builder builder =
                ChatMessage.builder()
                        .role(ChatMessageRole.TOOL)
                        .content(functionResponse.response().map(JSONUtil::toJson).orElse("{}"));
        functionResponse.id().filter(StringUtils::isNotBlank).ifPresent(builder::toolCallId);
        functionResponse.name().filter(StringUtils::isNotBlank).ifPresent(builder::name);
        return builder.build();
    }

    private ChatMessage buildFunctionCallMessage(Content content) {
        ChatMessage.Builder builder = ChatMessage.builder().role(ChatMessageRole.ASSISTANT);
        String text = extractText(content);
        if (StringUtils.isNotEmpty(text)) {
            builder.content(text);
        }
        List<ChatToolCall> toolCalls =
                content.parts().stream()
                        .flatMap(List::stream)
                        .filter(Objects::nonNull)
                        .flatMap(part -> part.functionCall().stream())
                        .map(this::toChatToolCall)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .toList();
        if (!toolCalls.isEmpty()) {
            builder.toolCalls(toolCalls);
        }
        return builder.build();
    }

    private Optional<ChatToolCall> toChatToolCall(FunctionCall functionCall) {
        Optional<String> name = functionCall.name().filter(StringUtils::isNotBlank);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        ChatFunctionCall chatFunctionCall = new ChatFunctionCall();
        chatFunctionCall.setName(name.get());
        chatFunctionCall.setArguments(functionCall.args().map(JSONUtil::toJson).orElse("{}"));
        ChatToolCall chatToolCall = new ChatToolCall();
        functionCall.id().filter(StringUtils::isNotBlank).ifPresent(chatToolCall::setId);
        chatToolCall.setType("function");
        chatToolCall.setFunction(chatFunctionCall);
        return Optional.of(chatToolCall);
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
        Optional<FunctionDeclaration> declaration = tool.declaration();
        if (declaration.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> schemaMap =
                declaration
                        .get()
                        .parameters()
                        .map(
                                schema ->
                                        JSONUtil.convertValue(
                                                schema,
                                                new TypeReference<Map<String, Object>>() {}))
                        .orElseGet(
                                () ->
                                        declaration
                                                .get()
                                                .parametersJsonSchema()
                                                .map(
                                                        schema ->
                                                                JSONUtil.convertValue(
                                                                        schema,
                                                                        new TypeReference<
                                                                                Map<
                                                                                        String,
                                                                                        Object>>() {}))
                                                .orElseGet(
                                                        () -> {
                                                            Map<String, Object> emptySchema =
                                                                    new LinkedHashMap<>();
                                                            emptySchema.put("type", "object");
                                                            emptySchema.put(
                                                                    "properties",
                                                                    new LinkedHashMap<>());
                                                            return emptySchema;
                                                        }));
        updateTypeString(schemaMap);

        ChatFunction chatFunction = new ChatFunction();
        chatFunction.setName(tool.name());
        chatFunction.setDescription(tool.description());
        chatFunction.setParameters(JSONUtil.valueToTree(schemaMap));
        return Optional.of(new ChatTool("function", chatFunction));
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

    private boolean hasSupportedMediaPart(Content content) {
        return content.parts().stream()
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .anyMatch(
                        part ->
                                part.inlineData()
                                                .flatMap(blob -> blob.mimeType())
                                                .filter(this::isSupportedMediaMimeType)
                                                .isPresent()
                                        || part.fileData()
                                                .flatMap(fileData -> fileData.mimeType())
                                                .filter(this::isSupportedMediaMimeType)
                                                .isPresent());
    }

    private List<ChatCompletionContentPart> extractContentParts(Content content) {
        List<ChatCompletionContentPart> contentParts = new ArrayList<>();
        content.parts()
                .ifPresent(
                        parts -> {
                            for (Part part : parts) {
                                if (part == null) {
                                    continue;
                                }
                                appendTextContentPart(part, contentParts);
                                appendInlineDataContentPart(part, contentParts);
                                appendFileDataContentPart(part, contentParts);
                            }
                        });
        return contentParts;
    }

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

    private void appendTextContentPart(Part part, List<ChatCompletionContentPart> contentParts) {
        part.text()
                .filter(StringUtils::isNotEmpty)
                .ifPresent(
                        text ->
                                contentParts.add(
                                        ChatCompletionContentPart.builder()
                                                .type("text")
                                                .text(text)
                                                .build()));
    }

    private void appendInlineDataContentPart(
            Part part, List<ChatCompletionContentPart> contentParts) {
        part.inlineData()
                .filter(blob -> blob.data().isPresent())
                .flatMap(
                        blob ->
                                toMediaContentPart(
                                        dataUri(
                                                blob.mimeType().orElse("application/octet-stream"),
                                                blob.data().get()),
                                        blob.mimeType().orElse("application/octet-stream"),
                                        part.videoMetadata()))
                .ifPresent(contentParts::add);
    }

    private void appendFileDataContentPart(
            Part part, List<ChatCompletionContentPart> contentParts) {
        part.fileData()
                .filter(fileData -> fileData.fileUri().isPresent())
                .flatMap(
                        fileData ->
                                toMediaContentPart(
                                        fileData.fileUri().get(),
                                        fileData.mimeType().orElse("application/octet-stream"),
                                        part.videoMetadata()))
                .ifPresent(contentParts::add);
    }

    private Optional<ChatCompletionContentPart> toMediaContentPart(
            String url, String mimeType, Optional<VideoMetadata> videoMetadata) {
        if (mimeType.startsWith("image/")) {
            return Optional.of(
                    ChatCompletionContentPart.builder()
                            .type("image_url")
                            .imageUrl(
                                    new ChatCompletionContentPart.ChatCompletionContentPartImageURL(
                                            url, "auto"))
                            .build());
        }
        if (mimeType.startsWith("video/")) {
            return Optional.of(
                    ChatCompletionContentPart.builder()
                            .type("video_url")
                            .videoUrl(
                                    new ChatCompletionContentPart.ChatCompletionContentPartVideoURL(
                                            url,
                                            videoMetadata.flatMap(VideoMetadata::fps).orElse(1.0)))
                            .build());
        }
        return Optional.empty();
    }

    private boolean isSupportedMediaMimeType(String mimeType) {
        return mimeType.startsWith("image/") || mimeType.startsWith("video/");
    }

    private String dataUri(String mimeType, byte[] data) {
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(data);
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
        ChatFunctionCall function = toolCall.getFunction();
        Map<String, Object> args =
                StringUtils.isBlank(function.getArguments())
                        ? Map.of()
                        : JSONUtil.fromJson(
                                function.getArguments(),
                                new TypeReference<Map<String, Object>>() {});
        FunctionCall.Builder builder = FunctionCall.builder().name(function.getName()).args(args);
        if (StringUtils.isNotBlank(toolCall.getId())) {
            builder.id(toolCall.getId());
        }
        return Part.builder().functionCall(builder.build()).build();
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
