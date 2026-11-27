/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volcengine.ark.runtime.model.Usage;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChunk;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.veadk.trace.StreamingTpotRecorder;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** One SDK subscription, including its transport cleanup. No response content is retained. */
final class ArkStreamingObservation {
    private static final ObjectMapper RAW_MAPPER = new ObjectMapper();
    private static final Set<String> NORMAL = Set.of("stop", "tool_calls", "function_call");
    private final StreamingTpotRecorder recorder;
    private final LongSupplier clock;
    private boolean invalid;
    private boolean attached;
    private boolean consumed;
    private boolean closed;
    private boolean ended;
    private long first;
    private long last;
    private long increments;
    private long rawIncrements;
    private long rawChunks;
    private long sdkChunks;
    private String rawFinish;
    private String sdkFinish;
    private Long rawTokens;
    private Long sdkTokens;
    private Long sdkReasoning;
    private Long rawReasoning;
    private boolean rawReasoningSeen;
    private boolean sdkReasoningSeen;

    ArkStreamingObservation(String model) {
        this(StreamingTpotRecorder.start(model), System::nanoTime);
    }

    ArkStreamingObservation(StreamingTpotRecorder recorder, LongSupplier clock) {
        this.recorder = recorder;
        this.clock = clock;
    }

    synchronized void attach() {
        if (attached) invalidate();
        attached = true;
    }

    synchronized void observe(ChatCompletionChunk chunk) {
        if (invalid || ended) return;
        try {
            sdkChunks++;
            Usage usage = chunk.getUsage();
            if (usage != null) {
                sdkTokens = usage.getCompletionTokens();
                sdkReasoning =
                        usage.getCompletionTokensDetails() == null
                                        || usage.getCompletionTokensDetails().getReasoningTokens()
                                                == null
                                ? null
                                : usage.getCompletionTokensDetails()
                                        .getReasoningTokens()
                                        .longValue();
            }
            if (chunk.getChoices() == null) {
                invalidate();
                return;
            }
            if (chunk.getChoices().isEmpty()) return;
            if (chunk.getChoices().size() != 1
                    || !Integer.valueOf(0).equals(chunk.getChoices().get(0).getIndex())) {
                invalidate();
                return;
            }
            var choice = chunk.getChoices().get(0);
            ChatMessage message = choice.getMessage();
            boolean output = false;
            if (message != null) {
                if (message.getContent() != null && !(message.getContent() instanceof String)) {
                    invalidate();
                    return;
                }
                output = nonempty((String) message.getContent());
                boolean reasoning = nonempty(message.getReasoningContent());
                sdkReasoningSeen |= reasoning;
                output |= reasoning;
                if (message.getToolCalls() != null) {
                    for (var call : message.getToolCalls()) {
                        output |=
                                call.getFunction() != null
                                        && nonempty(call.getFunction().getArguments());
                    }
                }
            }
            if (output) {
                if (sdkFinish != null) {
                    invalidate();
                    return;
                }
                long now = clock.getAsLong();
                if (increments == 0) first = now;
                else if (now - last < 0) invalidate();
                last = now;
                increments++;
            }
            String finish = choice.getFinishReason();
            if (nonempty(finish)) {
                if (sdkFinish != null || !NORMAL.contains(finish)) invalidate();
                sdkFinish = finish;
            }
        } catch (RuntimeException ignored) {
            invalidate();
        }
    }

    synchronized void raw(String data) {
        if (invalid || ended) return;
        try {
            JsonNode root = RAW_MAPPER.readTree(data);
            requireFields(
                    root,
                    Set.of(
                            "id",
                            "object",
                            "created",
                            "model",
                            "choices",
                            "usage",
                            "service_tier",
                            "system_fingerprint"));
            require(
                    !root.hasNonNull("object")
                            || "chat.completion.chunk".equals(root.path("object").textValue()));
            JsonNode choices = root.path("choices");
            require(choices.isArray() && choices.size() <= 1);
            rawChunks++;
            if (!choices.isEmpty()) {
                JsonNode choice = choices.get(0);
                requireFields(
                        choice,
                        Set.of(
                                "index",
                                "delta",
                                "message",
                                "finish_reason",
                                "logprobs",
                                "moderation_hit_type"));
                require(integer(choice.path("index")) == 0);
                require(
                        !choice.hasNonNull("moderation_hit_type")
                                || choice.path("moderation_hit_type").asText().isEmpty());
                require(!(choice.has("delta") && choice.has("message")));
                JsonNode message =
                        choice.has("delta") ? choice.get("delta") : choice.get("message");
                boolean output = false;
                if (message != null && !message.isNull()) {
                    requireFields(
                            message, Set.of("role", "content", "reasoning_content", "tool_calls"));
                    require(
                            !message.hasNonNull("role")
                                    || "assistant".equals(message.path("role").textValue()));
                    output = text(message.get("content"));
                    boolean reasoning = text(message.get("reasoning_content"));
                    rawReasoningSeen |= reasoning;
                    output |= reasoning;
                    JsonNode calls = message.get("tool_calls");
                    if (calls != null && !calls.isNull()) {
                        require(calls.isArray());
                        for (JsonNode call : calls) {
                            requireFields(call, Set.of("index", "id", "type", "function"));
                            require(
                                    !call.hasNonNull("type")
                                            || "function".equals(call.path("type").textValue()));
                            JsonNode function = call.get("function");
                            if (function != null && !function.isNull()) {
                                requireFields(function, Set.of("name", "arguments"));
                                output |= text(function.get("arguments"));
                            }
                        }
                    }
                }
                if (output) {
                    require(rawFinish == null);
                    rawIncrements++;
                }
                JsonNode finish = choice.get("finish_reason");
                if (text(finish)) {
                    require(rawFinish == null && NORMAL.contains(finish.textValue()));
                    rawFinish = finish.textValue();
                }
            }
            JsonNode usage = root.get("usage");
            if (usage != null && !usage.isNull()) {
                requireFields(
                        usage,
                        Set.of(
                                "prompt_tokens",
                                "completion_tokens",
                                "total_tokens",
                                "prompt_tokens_details",
                                "completion_tokens_details"));
                rawTokens = integer(usage.path("completion_tokens"));
                require(rawTokens >= 0);
                rawReasoning = null;
                JsonNode details = usage.get("completion_tokens_details");
                if (details != null && !details.isNull()) {
                    requireFields(details, Set.of("reasoning_tokens", "provisioned_tokens"));
                    if (details.has("reasoning_tokens")) {
                        rawReasoning = integer(details.get("reasoning_tokens"));
                        require(rawReasoning >= 0 && rawReasoning <= rawTokens);
                    }
                }
            }
        } catch (Exception ignored) {
            // Raw payloads must never appear in diagnostics or alter SDK parsing.
            invalidate();
        }
    }

    synchronized void complete() {
        consumed = true;
        if (!attached) invalidate();
        finishIfReady();
    }

    synchronized void closeSucceeded() {
        closed = true;
        finishIfReady();
    }

    synchronized void invalidate() {
        invalid = true;
        if (!ended) {
            ended = true;
            recorder.finish(null);
        }
    }

    private void finishIfReady() {
        if (ended || !consumed || !closed) return;
        ended = true;
        Double value = null;
        if (!invalid
                && attached
                && increments >= 2
                && increments == rawIncrements
                && sdkChunks == rawChunks
                && rawFinish != null
                && Objects.equals(rawFinish, sdkFinish)
                && rawTokens != null
                && rawTokens > 1
                && Objects.equals(rawTokens, sdkTokens)
                && Objects.equals(rawReasoning, sdkReasoning)
                && rawReasoningSeen == sdkReasoningSeen
                && (!rawReasoningSeen || rawReasoning != null)
                && (rawReasoning == null || rawReasoning == 0 || rawReasoningSeen)) {
            long duration = last - first;
            if (duration >= 0) value = duration / 1e9 / (rawTokens - 1);
        }
        recorder.finish(value);
    }

    private static boolean nonempty(String value) {
        return value != null && !value.isEmpty();
    }

    private static boolean text(JsonNode node) {
        if (node == null || node.isNull()) return false;
        require(node.isTextual());
        return !node.textValue().isEmpty();
    }

    private static long integer(JsonNode node) {
        require(node != null && node.isIntegralNumber() && node.canConvertToLong());
        return node.longValue();
    }

    private static void requireFields(JsonNode node, Set<String> allowed) {
        require(node != null && node.isObject());
        node.fieldNames().forEachRemaining(name -> require(allowed.contains(name)));
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Untrusted streaming observation");
    }
}
