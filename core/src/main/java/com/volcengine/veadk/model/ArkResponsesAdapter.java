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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.volcengine.ark.runtime.exception.ArkHttpException;
import com.volcengine.ark.runtime.model.responses.common.ResponsesThinking;
import com.volcengine.ark.runtime.model.responses.content.OutputContentItemText;
import com.volcengine.ark.runtime.model.responses.event.ErrorEvent;
import com.volcengine.ark.runtime.model.responses.event.outputtext.OutputTextDeltaEvent;
import com.volcengine.ark.runtime.model.responses.event.response.ResponseCompletedEvent;
import com.volcengine.ark.runtime.model.responses.event.response.ResponseFailedEvent;
import com.volcengine.ark.runtime.model.responses.event.response.ResponseInCompleteEvent;
import com.volcengine.ark.runtime.model.responses.item.BaseItem;
import com.volcengine.ark.runtime.model.responses.item.InputItem;
import com.volcengine.ark.runtime.model.responses.item.ItemEasyMessage;
import com.volcengine.ark.runtime.model.responses.item.ItemFunctionToolCall;
import com.volcengine.ark.runtime.model.responses.item.ItemOutputMessage;
import com.volcengine.ark.runtime.model.responses.item.MessageContent;
import com.volcengine.ark.runtime.model.responses.request.CreateResponsesRequest;
import com.volcengine.ark.runtime.model.responses.request.ResponsesInput;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;
import com.volcengine.ark.runtime.model.responses.tool.ResponsesTool;
import com.volcengine.ark.runtime.model.responses.tool.ToolFunction;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.veadk.utils.JSONUtil;
import io.reactivex.rxjava3.core.Flowable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Stateless per-request bridge to the Ark SDK Responses API. */
final class ArkResponsesAdapter {
    private static final String EXPIRED = "InvalidParameter.PreviousResponseNotFound";
    private final ArkService service;
    private final ArkLlmConfig config;

    ArkResponsesAdapter(ArkService service, ArkLlmConfig config) {
        this.service = service;
        this.config = config;
    }

    Flowable<LlmResponse> generate(LlmRequest request, boolean stream) {
        return Flowable.defer(
                () -> {
                    CreateResponsesRequest wire = toRequest(request, true);
                    boolean[] output = {false};
                    return attempt(request, wire, stream, output)
                            .onErrorResumeNext(
                                    error -> {
                                        if (wire.getPreviousResponseId() != null
                                                && !output[0]
                                                && expired(error)) {
                                            // Retry exactly once with full history and tool
                                            // declarations.
                                            return attempt(
                                                    request,
                                                    toRequest(request, false),
                                                    stream,
                                                    new boolean[] {false});
                                        }
                                        return Flowable.error(error);
                                    });
                });
    }

    private Flowable<LlmResponse> attempt(
            LlmRequest request, CreateResponsesRequest wire, boolean stream, boolean[] output) {
        if (!stream) {
            return Flowable.fromCallable(() -> service.createResponse(wire))
                    .map(raw -> toResponse(raw, request));
        }
        wire.setStream(true);
        boolean[] completed = {false};
        return Flowable.defer(() -> Flowable.fromPublisher(service.streamResponse(wire)))
                .takeUntil(
                        event ->
                                event instanceof ResponseCompletedEvent
                                        || event instanceof ResponseFailedEvent
                                        || event instanceof ResponseInCompleteEvent
                                        || event instanceof ErrorEvent)
                .concatMap(
                        event -> {
                            String type = event.getType();
                            if (type != null
                                    && (type.startsWith("response.output_")
                                            || type.startsWith("response.function_call_")
                                            || type.startsWith("response.reasoning"))) {
                                output[0] = true;
                            }
                            if (event instanceof OutputTextDeltaEvent delta) {
                                if (delta.getDelta() == null || delta.getDelta().isEmpty())
                                    return Flowable.empty();
                                return Flowable.just(
                                        LlmResponse.builder()
                                                .partial(true)
                                                .content(
                                                        Content.builder()
                                                                .role("model")
                                                                .parts(
                                                                        Part.fromText(
                                                                                delta.getDelta()))
                                                                .build())
                                                .build());
                            }
                            if (event instanceof ResponseCompletedEvent done) {
                                LlmResponse response = toResponse(done.getResponse(), request);
                                completed[0] = true;
                                output[0] = true;
                                return Flowable.just(response);
                            }
                            if (event instanceof ErrorEvent error) {
                                return Flowable.error(
                                        new ResponseFailure(error.getCode(), error.getMessage()));
                            }
                            if (event instanceof ResponseFailedEvent failed) {
                                return Flowable.error(failure(failed.getResponse()));
                            }
                            if (event instanceof ResponseInCompleteEvent incomplete) {
                                return Flowable.error(failure(incomplete.getResponse()));
                            }
                            return Flowable.<LlmResponse>empty();
                        })
                .concatWith(
                        Flowable.defer(
                                () ->
                                        completed[0]
                                                ? Flowable.empty()
                                                : Flowable.error(
                                                        new IllegalStateException(
                                                                "Ark Responses stream ended without"
                                                                        + " completion."))));
    }

    private CreateResponsesRequest toRequest(LlmRequest request, boolean reuse) {
        CreateResponsesRequest wire = new CreateResponsesRequest();
        wire.setModel(request.model().orElse(config.getModelName()));
        wire.setStore(config.isResponsesStore());
        if (config.getThinking() != null) {
            ResponsesThinking thinking = new ResponsesThinking();
            thinking.setType(config.getThinking());
            wire.setThinking(thinking);
        }
        request.config()
                .ifPresent(
                        generation -> {
                            generation
                                    .temperature()
                                    .ifPresent(value -> wire.setTemperature(value.doubleValue()));
                            generation.topP().ifPresent(value -> wire.setTopP(value.doubleValue()));
                            generation
                                    .maxOutputTokens()
                                    .ifPresent(value -> wire.setMaxOutputTokens(value.longValue()));
                            wire.setText(ArkStructuredOutput.responsesFormat(generation));
                        });
        List<InputItem> messages = new ArkResponsesInputMapper().map(request.contents());
        int skip = 0;
        if (reuse && config.isResponsesStore()) {
            JsonNode continuation = continuation(request);
            int count = continuation.path("count").asInt(-1);
            if (count >= 0
                    && count < request.contents().size()
                    && signature(request).equals(continuation.path("signature").asText())
                    && digest(JSONUtil.valueToTree(request.contents().subList(0, count)))
                            .equals(continuation.path("prefix").asText())
                    && !continuation.path("id").asText().isBlank()) {
                wire.setPreviousResponseId(continuation.path("id").asText());
                skip =
                        new ArkResponsesInputMapper()
                                .map(request.contents().subList(0, count))
                                .size();
            }
        }
        List<InputItem> input = new ArrayList<>();
        if (wire.getPreviousResponseId() == null) {
            for (String instruction : request.getSystemInstructions())
                input.add(message("system", instruction));
            var tools = tools(request);
            if (!tools.isEmpty()) wire.setTools(tools);
        }
        input.addAll(messages.subList(skip, messages.size()));
        ResponsesInput responsesInput = new ResponsesInput();
        responsesInput.setListValue(input);
        wire.setInput(responsesInput);
        return wire;
    }

    private static ItemEasyMessage message(String role, String text) {
        ItemEasyMessage message = new ItemEasyMessage();
        message.setRole(role);
        MessageContent content = new MessageContent();
        content.setStringValue(text);
        message.setContent(content);
        return message;
    }

    private static List<ResponsesTool> tools(LlmRequest request) {
        List<ResponsesTool> tools = new ArrayList<>();
        request.tools()
                .values()
                .forEach(
                        tool ->
                                tool.declaration()
                                        .ifPresent(
                                                declaration -> {
                                                    ToolFunction function = new ToolFunction();
                                                    function.setName(
                                                            declaration.name().orElse(tool.name()));
                                                    function.setDescription(
                                                            declaration
                                                                    .description()
                                                                    .orElse(tool.description()));
                                                    Object schema =
                                                            declaration
                                                                    .parametersJsonSchema()
                                                                    .orElseGet(
                                                                            () ->
                                                                                    declaration
                                                                                            .parameters()
                                                                                            .orElse(
                                                                                                    null));
                                                    JsonNode parameters =
                                                            JSONUtil.valueToTree(
                                                                            schema == null
                                                                                    ? Map.of(
                                                                                            "type",
                                                                                            "object",
                                                                                            "properties",
                                                                                            Map
                                                                                                    .of())
                                                                                    : schema)
                                                                    .deepCopy();
                                                    ArkStructuredOutput.normalize(parameters);
                                                    function.setParameters(parameters);
                                                    tools.add(function);
                                                }));
        return tools;
    }

    private LlmResponse toResponse(ResponseObject raw, LlmRequest request) {
        if (raw == null || !"completed".equals(raw.getStatus())) throw failure(raw);
        List<Part> parts = new ArrayList<>();
        if (raw.getOutput() != null)
            for (BaseItem item : raw.getOutput()) {
                if (item instanceof ItemOutputMessage message && message.getContent() != null) {
                    for (var content : message.getContent()) {
                        if (content instanceof OutputContentItemText text
                                && text.getText() != null) {
                            parts.add(Part.fromText(text.getText()));
                        }
                    }
                } else if (item instanceof ItemFunctionToolCall call) {
                    if (call.getCallId() == null
                            || call.getCallId().isBlank()
                            || call.getName() == null
                            || call.getName().isBlank()) {
                        throw new IllegalArgumentException(
                                "Ark function call requires call_id and name.");
                    }
                    try {
                        JsonNode args = JSONUtil.parseJson(call.getArguments());
                        if (args == null || !args.isObject())
                            throw new IllegalArgumentException(
                                    "Tool arguments must be a JSON object.");
                        Map<String, Object> values =
                                JSONUtil.convertValue(
                                        args,
                                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
                        parts.add(
                                Part.builder()
                                        .functionCall(
                                                FunctionCall.builder()
                                                        .id(call.getCallId())
                                                        .name(call.getName())
                                                        .args(values)
                                                        .build())
                                        .build());
                    } catch (java.io.IOException e) {
                        throw new IllegalArgumentException("Invalid JSON tool arguments.", e);
                    }
                }
            }
        if (parts.isEmpty())
            throw new IllegalStateException("Ark Responses returned no supported output.");
        Content content = Content.builder().role("model").parts(parts).build();
        var builder =
                LlmResponse.builder()
                        .content(content)
                        .partial(false)
                        .finishReason(new FinishReason("STOP"));
        if (raw.getModel() != null) builder.modelVersion(raw.getModel());
        var usage = raw.getUsage();
        if (usage != null) {
            var metadata = GenerateContentResponseUsageMetadata.builder();
            if (usage.getInputTokens() != null)
                metadata.promptTokenCount(Math.toIntExact(usage.getInputTokens()));
            if (usage.getOutputTokens() != null)
                metadata.candidatesTokenCount(Math.toIntExact(usage.getOutputTokens()));
            if (usage.getTotalTokens() != null)
                metadata.totalTokenCount(Math.toIntExact(usage.getTotalTokens()));
            if (usage.getInputTokensDetails() != null
                    && usage.getInputTokensDetails().getCachedTokens() != null) {
                metadata.cachedContentTokenCount(
                        Math.toIntExact(usage.getInputTokensDetails().getCachedTokens()));
            }
            builder.usageMetadata(metadata.build());
        }
        if (raw.getId() != null && !raw.getId().isBlank()) {
            List<CustomMetadata> metadata = new ArrayList<>();
            metadata.add(
                    CustomMetadata.builder()
                            .key(ArkResponsesSession.RESPONSE_ID)
                            .stringValue(raw.getId())
                            .build());
            if (config.isResponsesStore()) {
                List<Content> history = new ArrayList<>(request.contents());
                history.add(content);
                String token =
                        JSONUtil.valueToTree(
                                        Map.of(
                                                "id",
                                                raw.getId(),
                                                "count",
                                                history.size(),
                                                "prefix",
                                                digest(JSONUtil.valueToTree(history)),
                                                "signature",
                                                signature(request)))
                                .toString();
                metadata.add(
                        CustomMetadata.builder()
                                .key(ArkResponsesSession.CONTEXT)
                                .stringValue(token)
                                .build());
            }
            builder.customMetadata(metadata);
        }
        return builder.build();
    }

    private String signature(LlmRequest request) {
        ObjectNode generation =
                request.config()
                        .map(JSONUtil::valueToTree)
                        .filter(JsonNode::isObject)
                        .map(node -> (ObjectNode) node.deepCopy())
                        .orElseGet(() -> JsonNodeFactory.instance.objectNode());
        generation.remove(List.of("httpOptions", "http_options"));
        return digest(
                JSONUtil.valueToTree(
                        Map.of(
                                "model",
                                request.model().orElse(config.getModelName()),
                                "endpoint",
                                config.getApiBase(),
                                "thinking",
                                config.getThinking() == null ? "" : config.getThinking(),
                                "generation",
                                generation,
                                "tools",
                                tools(request))));
    }

    private static JsonNode continuation(LlmRequest request) {
        Object token =
                request.config()
                        .flatMap(com.google.genai.types.GenerateContentConfig::httpOptions)
                        .flatMap(com.google.genai.types.HttpOptions::extraBody)
                        .orElse(Map.of())
                        .get(ArkResponsesSession.CONTEXT);
        if (token instanceof String text) {
            try {
                JsonNode parsed = JSONUtil.parseJson(text);
                if (parsed != null && parsed.isObject()) return parsed;
            } catch (java.io.IOException ignored) {
                /* Invalid or obsolete local state: use full history. */
            }
        }
        return JsonNodeFactory.instance.objectNode();
    }

    private static String digest(JsonNode node) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(canonical(node).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, String> sorted = new TreeMap<>();
            node.fields()
                    .forEachRemaining(
                            entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            return sorted.entrySet().stream()
                    .map(entry -> JSONUtil.valueToTree(entry.getKey()) + ":" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (node.isArray()) {
            List<String> entries = new ArrayList<>();
            node.forEach(child -> entries.add(canonical(child)));
            return String.join(",", entries).transform(value -> "[" + value + "]");
        }
        return node.toString();
    }

    private static RuntimeException failure(ResponseObject response) {
        if (response != null && response.getError() != null) {
            return new ResponseFailure(
                    response.getError().getCode(), response.getError().getMessage());
        }
        return new IllegalStateException(
                "Ark response did not complete: "
                        + (response == null
                                ? "missing response"
                                : response.getStatus()
                                        + (response.getIncompleteDetails() == null
                                                ? ""
                                                : " ("
                                                        + response.getIncompleteDetails()
                                                                .getReason()
                                                        + ")")));
    }

    private static boolean expired(Throwable error) {
        return error instanceof ArkHttpException http && EXPIRED.equals(http.code)
                || error instanceof ResponseFailure failure && EXPIRED.equals(failure.code);
    }

    private static final class ResponseFailure extends RuntimeException {
        private final String code;

        ResponseFailure(String code, String message) {
            super("Ark Responses error " + code + ": " + message);
            this.code = code;
        }
    }
}
