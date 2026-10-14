/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpIdentityClientTest {
    @Test
    void signsAndParsesWorkloadAndPermissionApis() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    path.set(exchange.getRequestURI().getPath());
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    String response =
                            path.get().contains("CheckPermission")
                                    ? "{\"Allowed\":true}"
                                    : path.get().contains("GetResourceApiKey")
                                            ? "{\"ApiKey\":\"managed-key\"}"
                                            : path.get().contains("GetResourceOauth2Token")
                                                    ? "{\"AuthorizationUrl\":\"https://login.example.com\"}"
                                                    : "{\"WorkloadAccessToken\":\"jwt-value\",\"ExpiresAt\":\"2026-10-14T01:00:00Z\"}";
                    respond(exchange, response);
                });
        server.start();
        try {
            HttpIdentityClient client = client(server);
            WorkloadToken token = client.getWorkloadToken("agent-a", "user-a", null);
            assertThat(path.get())
                    .isEqualTo(
                            "/GetWorkloadAccessTokenForUserId/2025-10-30/id/post/application_json/");
            assertThat(body.get())
                    .contains("\"Name\":\"agent-a\"")
                    .contains("\"UserId\":\"user-a\"");
            assertThat(token.value()).isEqualTo("jwt-value");
            assertThat(auth.get())
                    .startsWith("HMAC-SHA256 Credential=ak/20261014/cn-test/id/request");

            boolean allowed =
                    client.checkPermission(
                            new PermissionRequest(
                                    "default",
                                    new PermissionRequest.Principal("user", "u"),
                                    new PermissionRequest.Principal("Action", "invoke"),
                                    new PermissionRequest.Principal("tool", "search"),
                                    List.of()));
            assertThat(allowed).isTrue();
            assertThat(body.get())
                    .contains("\"NamespaceName\":\"default\"")
                    .contains("\"Type\":\"tool\"")
                    .contains("\"Id\":\"search\"");

            assertThat(client.getApiKey("github", "identity-token")).isEqualTo("managed-key");
            assertThat(body.get())
                    .contains("\"ProviderName\":\"github\"")
                    .contains("\"IdentityToken\":\"identity-token\"");
            OAuth2Credential oauth =
                    client.getOAuth2Credential(
                            "github",
                            "identity-token",
                            "USER_FEDERATION",
                            List.of("repo"),
                            "https://callback",
                            true);
            assertThat(oauth.type()).isEqualTo(OAuth2Credential.Type.AUTHORIZATION_URL);
            assertThat(oauth.value()).isEqualTo("https://login.example.com");
            assertThat(body.get())
                    .contains("\"Flow\":\"USER_FEDERATION\"")
                    .contains("\"Scopes\":[\"repo\"]")
                    .contains("\"ForceAuthentication\":true");
        } finally {
            server.stop(0);
        }
    }

    private static HttpIdentityClient client(HttpServer server) {
        return new HttpIdentityClient(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "cn-test",
                "ak",
                "sk",
                "session",
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
