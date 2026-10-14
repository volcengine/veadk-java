/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import com.google.adk.tools.BaseTool;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IdentitySecurityTest {
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void tokenCacheIsScopedAndRefreshesAtSixtySecondBoundary() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        IdentityClient client =
                new StubClient((request) -> true) {
                    @Override
                    public WorkloadToken getWorkloadToken(
                            String agent, String user, String ignored) {
                        calls.incrementAndGet();
                        Instant expiry =
                                user.equals("refresh") ? NOW.plusSeconds(60) : NOW.plusSeconds(61);
                        return new WorkloadToken(agent + ":" + user, expiry);
                    }
                };
        WorkloadTokenManager manager =
                new WorkloadTokenManager(
                        client, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60));
        Map<String, Object> state = new HashMap<>();

        assertThat(manager.get(state, "agent-a", "user-a")).isEqualTo("agent-a:user-a");
        assertThat(manager.get(state, "agent-a", "user-a")).isEqualTo("agent-a:user-a");
        assertThat(manager.get(state, "agent-b", "user-a")).isEqualTo("agent-b:user-a");
        assertThat(manager.get(state, "agent-a", "refresh")).isEqualTo("agent-a:refresh");
        assertThat(manager.get(state, "agent-a", "refresh")).isEqualTo("agent-a:refresh");

        assertThat(calls).hasValue(4);
        assertThat(state)
                .containsKeys(
                        "workload_token:agent-a:user-a",
                        "workload_token:agent-b:user-a",
                        "workload_token:agent-a:refresh");
    }

    @Test
    void agentAndToolCallbacksBuildDelegationAwarePermissionRequests() {
        AtomicReference<PermissionRequest> captured = new AtomicReference<>();
        StubClient client =
                new StubClient(
                        request -> {
                            captured.set(request);
                            return true;
                        });
        IdentityAuthorization authorization = new IdentityAuthorization(client);
        State state = new State(new HashMap<>());
        CallbackContext callbackContext = mock(CallbackContext.class);
        when(callbackContext.state()).thenReturn(state);
        when(callbackContext.agentName()).thenReturn("runtime-agent");
        when(callbackContext.userId()).thenReturn("session-user");

        assertThat(authorization.beforeAgentCallback().call(callbackContext).blockingGet())
                .isNull();
        assertThat(captured.get().principal().id()).isEqualTo("user-1");
        assertThat(captured.get().resource())
                .isEqualTo(new PermissionRequest.Principal("agent", "agent-2"));
        assertThat(captured.get().originalCallers())
                .containsExactly(new PermissionRequest.Principal("agent", "agent-1"));

        InvocationContext invocation = mock(InvocationContext.class);
        Session session = mock(Session.class);
        BaseTool tool = mock(BaseTool.class);
        com.google.adk.agents.BaseAgent agent = mock(com.google.adk.agents.BaseAgent.class);
        when(invocation.session()).thenReturn(session);
        when(session.state()).thenReturn(state);
        when(invocation.agent()).thenReturn(agent);
        when(agent.name()).thenReturn("runtime-agent");
        when(invocation.userId()).thenReturn("session-user");
        when(tool.name()).thenReturn("database_query");

        assertThat(
                        authorization
                                .beforeToolCallback()
                                .call(invocation, tool, Map.of(), null)
                                .blockingGet())
                .isNull();
        assertThat(captured.get().resource())
                .isEqualTo(new PermissionRequest.Principal("tool", "database_query"));
        assertThat(captured.get().originalCallers())
                .containsExactly(
                        new PermissionRequest.Principal("agent", "agent-2"),
                        new PermissionRequest.Principal("agent", "agent-1"));
    }

    @Test
    void authorizationFailsClosedForDenialMalformedJwtAndClientError() {
        CallbackContext context = mock(CallbackContext.class);
        when(context.state()).thenReturn(new State(new HashMap<>()));
        when(context.agentName()).thenReturn("agent");
        when(context.userId()).thenReturn("user");

        IdentityAuthorization denied = new IdentityAuthorization(new StubClient(request -> false));
        assertThat(denied.beforeAgentCallback().call(context).blockingGet().text())
                .contains("not authorized")
                .doesNotContain(jwt());

        IdentityAuthorization malformed =
                new IdentityAuthorization(
                        new StubClient(request -> true) {
                            @Override
                            public WorkloadToken getWorkloadToken(String a, String u, String t) {
                                return new WorkloadToken("not-a-jwt", NOW.plusSeconds(300));
                            }
                        });
        assertThat(malformed.beforeAgentCallback().call(context).blockingGet().text())
                .isEqualTo("Failed to verify authorization.");

        IdentityAuthorization error =
                new IdentityAuthorization(
                        new StubClient(
                                request -> {
                                    throw new Exception("secret-token");
                                }));
        assertThat(error.beforeAgentCallback().call(context).blockingGet().text())
                .isEqualTo("Failed to verify authorization.")
                .doesNotContain("secret-token");
    }

    @Test
    void managedApiKeyIsCachedPerAgentUserAndProvider() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StubClient client =
                new StubClient(request -> true) {
                    @Override
                    public String getApiKey(String provider, String token) {
                        calls.incrementAndGet();
                        return provider + "-key";
                    }
                };
        IdentityCredentials credentials = new IdentityCredentials(client);
        Map<String, Object> state = new HashMap<>();

        assertThat(credentials.apiKey(state, "agent", "user", "github")).isEqualTo("github-key");
        assertThat(credentials.apiKey(state, "agent", "user", "github")).isEqualTo("github-key");
        assertThat(credentials.apiKey(state, "agent", "user", "slack")).isEqualTo("slack-key");
        assertThat(calls).hasValue(2);
    }

    private static String jwt() {
        String header =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                "{\"sub\":\"user-1\",\"act\":{\"sub\":\"agent-2\",\"act\":{\"sub\":\"agent-1\"}}}"
                                        .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".signature";
    }

    private interface PermissionCheck {
        boolean check(PermissionRequest request) throws Exception;
    }

    private static class StubClient implements IdentityClient {
        private final PermissionCheck check;

        StubClient(PermissionCheck check) {
            this.check = check;
        }

        @Override
        public WorkloadToken getWorkloadToken(String a, String u, String t) {
            return new WorkloadToken(jwt(), NOW.plusSeconds(300));
        }

        @Override
        public boolean checkPermission(PermissionRequest request) throws Exception {
            return check.check(request);
        }
    }
}
