/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.agents.InvocationContext;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import com.google.adk.tools.BaseTool;
import com.volcengine.veadk.security.identity.IdentityAuthorization;
import com.volcengine.veadk.security.identity.IdentityClient;
import com.volcengine.veadk.security.identity.PermissionRequest;
import com.volcengine.veadk.security.identity.WorkloadToken;
import com.volcengine.veadk.security.shield.LlmShieldGuardrail;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentSecurityTest {
    @Test
    void toolAuthorizationRunsBeforeShieldModeration() {
        AtomicInteger moderationCalls = new AtomicInteger();
        LlmShieldGuardrail shield =
                new LlmShieldGuardrail(
                        (m, r, h, s, i) -> {
                            moderationCalls.incrementAndGet();
                            return Optional.of("shield-blocked");
                        });
        InvocationContext invocation = invocation();
        BaseTool tool = mock(BaseTool.class);
        when(tool.name()).thenReturn("search");

        AgentSecurity denied =
                new AgentSecurity(new IdentityAuthorization(identity(false)), shield);
        assertThat(
                        denied.beforeToolCallback()
                                .call(invocation, tool, Map.of("q", "x"), null)
                                .blockingGet()
                                .get("result")
                                .toString())
                .contains("not authorized");
        assertThat(moderationCalls).hasValue(0);

        AgentSecurity allowed =
                new AgentSecurity(new IdentityAuthorization(identity(true)), shield);
        assertThat(
                        allowed.beforeToolCallback()
                                .call(invocation, tool, Map.of("q", "x"), null)
                                .blockingGet())
                .containsEntry("result", "shield-blocked");
        assertThat(moderationCalls).hasValue(1);
    }

    private static InvocationContext invocation() {
        InvocationContext invocation = mock(InvocationContext.class);
        Session session = mock(Session.class);
        com.google.adk.agents.BaseAgent agent = mock(com.google.adk.agents.BaseAgent.class);
        when(invocation.session()).thenReturn(session);
        when(session.state()).thenReturn(new State(new HashMap<>()));
        when(session.id()).thenReturn("session");
        when(invocation.agent()).thenReturn(agent);
        when(agent.name()).thenReturn("agent");
        when(invocation.userId()).thenReturn("user");
        when(invocation.invocationId()).thenReturn("run");
        return invocation;
    }

    private static IdentityClient identity(boolean allowed) {
        return new IdentityClient() {
            @Override
            public WorkloadToken getWorkloadToken(String a, String u, String t) {
                String payload =
                        Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        "{\"sub\":\"user\",\"act\":{\"sub\":\"agent\"}}"
                                                .getBytes(StandardCharsets.UTF_8));
                return new WorkloadToken("e30." + payload + ".sig", Instant.now().plusSeconds(300));
            }

            @Override
            public boolean checkPermission(PermissionRequest request) {
                return allowed;
            }
        };
    }
}
