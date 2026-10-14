/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.shield;

import com.google.adk.agents.Callbacks;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Four-hook LLM Shield guardrail compatible with veadk-python. */
public final class LlmShieldGuardrail {
    private static final Logger LOGGER = LoggerFactory.getLogger(LlmShieldGuardrail.class);
    private static final String FAILURE =
            "Your request has been blocked because content moderation is unavailable.";
    private final LlmShieldClient client;
    private final FailureMode failureMode;
    private final Set<String> protectedTools;

    public enum FailureMode {
        ALLOW,
        BLOCK
    }

    public LlmShieldGuardrail(LlmShieldClient client) {
        this(client, FailureMode.ALLOW, Set.of());
    }

    public LlmShieldGuardrail(
            LlmShieldClient client, FailureMode failureMode, Set<String> protectedTools) {
        this.client = Objects.requireNonNull(client);
        this.failureMode = Objects.requireNonNull(failureMode);
        this.protectedTools = protectedTools == null ? Set.of() : Set.copyOf(protectedTools);
    }

    public Callbacks.BeforeModelCallback beforeModelCallback() {
        return (context, request) ->
                Maybe.fromCallable(
                        () -> {
                            List<Content> contents = request.build().contents();
                            if (contents.isEmpty()) return null;
                            Content last = contents.get(contents.size() - 1);
                            if (!"user".equals(last.role().orElse("")) || last.text().isBlank())
                                return null;
                            String blocked =
                                    moderate(
                                            last.text(),
                                            "user",
                                            "before_model",
                                            context.sessionId(),
                                            context.invocationId());
                            return blocked == null ? null : response(blocked);
                        });
    }

    public Callbacks.AfterModelCallback afterModelCallback() {
        return (context, response) ->
                Maybe.fromCallable(
                        () -> {
                            Content content = response.content().orElse(null);
                            if (content == null
                                    || !"model".equals(content.role().orElse(""))
                                    || content.text().isBlank()) return null;
                            String blocked =
                                    moderate(
                                            content.text(),
                                            "assistant",
                                            "after_model",
                                            context.sessionId(),
                                            context.invocationId());
                            return blocked == null ? null : response(blocked);
                        });
    }

    public Callbacks.BeforeToolCallback beforeToolCallback() {
        return (invocation, tool, args, toolContext) ->
                Maybe.fromCallable(
                        () -> {
                            if (!protects(tool)) return null;
                            List<String> lines = new ArrayList<>();
                            args.forEach((key, value) -> lines.add(key + ": " + value));
                            String blocked =
                                    moderate(
                                            String.join("\n", lines),
                                            "user",
                                            "before_tool_call",
                                            invocation.session().id(),
                                            invocation.invocationId());
                            return blocked == null ? null : Map.of("result", blocked);
                        });
    }

    public Callbacks.AfterToolCallback afterToolCallback() {
        return (invocation, tool, args, toolContext, toolResponse) ->
                Maybe.fromCallable(
                        () -> {
                            if (!protects(tool)) return null;
                            String message =
                                    toolResponse instanceof Map<?, ?> map
                                            ? String.join(
                                                    "\n",
                                                    map.values().stream()
                                                            .map(String::valueOf)
                                                            .toList())
                                            : String.valueOf(toolResponse);
                            String blocked =
                                    moderate(
                                            message,
                                            "assistant",
                                            "after_tool_call",
                                            invocation.session().id(),
                                            invocation.invocationId());
                            return blocked == null ? null : Map.of("result", blocked);
                        });
    }

    private boolean protects(BaseTool tool) {
        return protectedTools.isEmpty() || protectedTools.contains(tool.name());
    }

    private String moderate(
            String message, String role, String hook, String sessionId, String invocationId) {
        if (message == null || message.isBlank()) return null;
        try {
            return client.moderate(message, role, hook, sessionId, invocationId).orElse(null);
        } catch (Exception e) {
            LOGGER.warn("LLM Shield {} failed; failure mode is {}.", hook, failureMode, e);
            return failureMode == FailureMode.BLOCK ? FAILURE : null;
        }
    }

    private static LlmResponse response(String text) {
        Content content =
                Content.builder().role("model").parts(List.of(Part.fromText(text))).build();
        return LlmResponse.builder().content(content).partial(true).build();
    }
}
