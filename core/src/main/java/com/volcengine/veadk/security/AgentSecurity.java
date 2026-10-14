/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security;

import com.google.adk.agents.Callbacks;
import com.volcengine.veadk.security.identity.IdentityAuthorization;
import com.volcengine.veadk.security.shield.LlmShieldGuardrail;
import java.util.Objects;

/** Composes Identity authorization and LLM Shield callbacks in security-first order. */
public final class AgentSecurity {
    private final IdentityAuthorization identity;
    private final LlmShieldGuardrail shield;

    public AgentSecurity(IdentityAuthorization identity, LlmShieldGuardrail shield) {
        this.identity = Objects.requireNonNull(identity, "identity must be set.");
        this.shield = Objects.requireNonNull(shield, "shield must be set.");
    }

    public Callbacks.BeforeAgentCallback beforeAgentCallback() {
        return identity.beforeAgentCallback();
    }

    public Callbacks.BeforeModelCallback beforeModelCallback() {
        return shield.beforeModelCallback();
    }

    public Callbacks.AfterModelCallback afterModelCallback() {
        return shield.afterModelCallback();
    }

    /** Denied tool calls stop immediately; authorized calls continue to content moderation. */
    public Callbacks.BeforeToolCallback beforeToolCallback() {
        Callbacks.BeforeToolCallback authorization = identity.beforeToolCallback();
        Callbacks.BeforeToolCallback moderation = shield.beforeToolCallback();
        return (invocation, tool, args, context) ->
                authorization
                        .call(invocation, tool, args, context)
                        .switchIfEmpty(moderation.call(invocation, tool, args, context));
    }

    public Callbacks.AfterToolCallback afterToolCallback() {
        return shield.afterToolCallback();
    }
}
