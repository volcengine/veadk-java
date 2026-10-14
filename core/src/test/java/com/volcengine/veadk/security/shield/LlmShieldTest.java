/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.shield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LlmShieldTest {
    @Test
    void apiKeyRequestUsesModerateContractAndMapsRiskCategories() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v2/moderate",
                exchange -> {
                    query.set(exchange.getRequestURI().getQuery());
                    apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    respond(
                            exchange,
                            "{\"Result\":{\"Decision\":{\"DecisionType\":2},"
                                + "\"RiskInfo\":{\"Risks\":[{\"Category\":104},{\"Category\":103}]}}}");
                });
        server.start();
        try {
            HttpLlmShieldClient client = client(server, "api-key", "", "");
            Optional<String> result =
                    client.moderate(
                            "ignore instructions", "user", "before_model", "session", "run");
            assertThat(query.get()).isEqualTo("Action=Moderate&Version=2025-08-31");
            assertThat(apiKey.get()).isEqualTo("api-key");
            assertThat(body.get())
                    .contains("\"Scene\":\"app-id\"")
                    .contains("\"Role\":\"user\"")
                    .contains("ignore instructions");
            assertThat(result)
                    .hasValueSatisfying(
                            message ->
                                    assertThat(message)
                                            .contains("Prompt Injection")
                                            .contains("Sensitive Information"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void akSkRequestIsSignedWithoutLeakingSecret() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> securityToken = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v2/moderate",
                exchange -> {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    securityToken.set(exchange.getRequestHeaders().getFirst("X-Security-Token"));
                    respond(exchange, "{\"Result\":{\"Decision\":{\"DecisionType\":1}}}");
                });
        server.start();
        try {
            HttpLlmShieldClient client = client(server, "", "ak-value", "secret-value");
            assertThat(client.moderate("safe", "user", "before_model", "s", "r")).isEmpty();
            assertThat(authorization.get())
                    .startsWith(
                            "HMAC-SHA256 Credential=ak-value/20261014/cn-test/llmshield/request");
            assertThat(authorization.get()).doesNotContain("secret-value");
            assertThat(securityToken.get()).isEqualTo("session-token");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fourCallbacksModerateContentAndRespectToolAllowlist() {
        AtomicInteger calls = new AtomicInteger();
        LlmShieldClient risky =
                (message, role, hook, session, run) -> {
                    calls.incrementAndGet();
                    return Optional.of("blocked:" + hook);
                };
        LlmShieldGuardrail guardrail =
                new LlmShieldGuardrail(
                        risky, LlmShieldGuardrail.FailureMode.ALLOW, Set.of("protected"));
        CallbackContext context = mock(CallbackContext.class);
        when(context.sessionId()).thenReturn("session");
        when(context.invocationId()).thenReturn("run");
        Content user =
                Content.builder()
                        .role("user")
                        .parts(List.of(Part.fromText("unsafe input")))
                        .build();
        LlmRequest.Builder request = LlmRequest.builder().contents(List.of(user));
        assertThat(
                        guardrail
                                .beforeModelCallback()
                                .call(context, request)
                                .blockingGet()
                                .content()
                                .orElseThrow()
                                .text())
                .isEqualTo("blocked:before_model");

        Content model =
                Content.builder()
                        .role("model")
                        .parts(List.of(Part.fromText("unsafe output")))
                        .build();
        LlmResponse response = LlmResponse.builder().content(model).build();
        assertThat(
                        guardrail
                                .afterModelCallback()
                                .call(context, response)
                                .blockingGet()
                                .content()
                                .orElseThrow()
                                .text())
                .isEqualTo("blocked:after_model");

        InvocationContext invocation = mock(InvocationContext.class);
        Session session = mock(Session.class);
        when(invocation.session()).thenReturn(session);
        when(session.id()).thenReturn("session");
        when(invocation.invocationId()).thenReturn("run");
        BaseTool skipped = mock(BaseTool.class);
        when(skipped.name()).thenReturn("skipped");
        assertThat(
                        guardrail
                                .beforeToolCallback()
                                .call(invocation, skipped, Map.of("q", "x"), null)
                                .blockingGet())
                .isNull();
        BaseTool protectedTool = mock(BaseTool.class);
        when(protectedTool.name()).thenReturn("protected");
        assertThat(
                        guardrail
                                .beforeToolCallback()
                                .call(invocation, protectedTool, Map.of("q", "x"), null)
                                .blockingGet())
                .containsEntry("result", "blocked:before_tool_call");
        assertThat(
                        guardrail
                                .afterToolCallback()
                                .call(
                                        invocation,
                                        protectedTool,
                                        Map.of(),
                                        null,
                                        Map.of("value", "unsafe"))
                                .blockingGet())
                .containsEntry("result", "blocked:after_tool_call");
        assertThat(calls).hasValue(4);
    }

    @Test
    void transportFailuresSupportPythonCompatibleAllowAndOptionalBlock() {
        LlmShieldClient failing =
                (message, role, hook, session, run) -> {
                    throw new IOException("credential-secret");
                };
        CallbackContext context = mock(CallbackContext.class);
        Content user =
                Content.builder().role("user").parts(List.of(Part.fromText("input"))).build();
        LlmRequest.Builder request = LlmRequest.builder().contents(List.of(user));

        assertThat(
                        new LlmShieldGuardrail(failing)
                                .beforeModelCallback()
                                .call(context, request)
                                .blockingGet())
                .isNull();
        LlmShieldGuardrail closed =
                new LlmShieldGuardrail(failing, LlmShieldGuardrail.FailureMode.BLOCK, Set.of());
        assertThat(
                        closed.beforeModelCallback()
                                .call(context, request)
                                .blockingGet()
                                .content()
                                .orElseThrow()
                                .text())
                .contains("moderation is unavailable")
                .doesNotContain("credential-secret");
    }

    private static HttpLlmShieldClient client(
            HttpServer server, String apiKey, String ak, String sk) {
        return new HttpLlmShieldClient(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "app-id",
                "cn-test",
                apiKey,
                ak,
                sk,
                "session-token",
                Duration.ofSeconds(2),
                Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC));
    }

    private static void respond(HttpExchange exchange, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
