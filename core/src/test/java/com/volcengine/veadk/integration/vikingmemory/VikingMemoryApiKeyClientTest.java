package com.volcengine.veadk.integration.vikingmemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.volcengine.veadk.integration.viking.VikingDataPlaneException;
import com.volcengine.veadk.utils.JSONUtil;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class VikingMemoryApiKeyClientTest {
    @Test
    void addAndSearchSendExpectedContract() throws Exception {
        FakeTransport transport = new FakeTransport();
        VikingMemoryApiKeyClient client =
                new VikingMemoryApiKeyClient("fake-secret", "https://example.com", "p", transport);
        transport.response =
                new VikingMemoryApiKeyClient.Response(
                        200, "{\"code\":0,\"data\":{\"session_id\":\"s1\"}}", "header-id");
        assertTrue(
                client.addSession(
                        "c",
                        "session_1",
                        List.of(new Message("user", "hello")),
                        new Metadata("u", "assistant", 12L)));
        JsonNode add = JSONUtil.parseJson(transport.body);
        assertEquals("https://example.com/api/memory/session/add", transport.url);
        assertEquals("fake-secret", transport.apiKey);
        assertEquals("p", add.path("project_name").asText());
        assertEquals(12L, add.path("metadata").path("time").asLong());
        assertFalse(add.has("profiles"));

        transport.response =
                new VikingMemoryApiKeyClient.Response(
                        200,
                        "{\"code\":0,\"data\":{\"result_list\":[{\"memory_info\":{\"summary\":\"answer\"}}]}}",
                        null);
        assertEquals(1, client.searchMemory("c", "u", "q", 5, List.of("sys_event_v1")).size());
        JsonNode search = JSONUtil.parseJson(transport.body);
        assertEquals("https://example.com/api/memory/search", transport.url);
        assertEquals(5, search.path("limit").asInt());
        assertEquals("u", search.path("filter").path("user_id").asText());
    }

    @Test
    void failuresAreDistinctAndSecretSafe() {
        FakeTransport transport = new FakeTransport();
        VikingMemoryApiKeyClient client =
                new VikingMemoryApiKeyClient(
                        "unique-fake-secret", "https://example.com", "p", transport);
        transport.response =
                new VikingMemoryApiKeyClient.Response(
                        403, "{\"code\":1001,\"request_id\":\"body-id\"}", "header-id");
        VikingDataPlaneException error =
                assertThrows(
                        VikingDataPlaneException.class,
                        () -> client.searchMemory("c", "u", "q", 5, List.of("t")));
        assertEquals("body-id", error.getRequestId());
        assertFalse(error.toString().contains("unique-fake-secret"));

        transport.response =
                new VikingMemoryApiKeyClient.Response(200, "{\"code\":0,\"data\":{}}", null);
        assertTrue(client.searchMemory("c", "u", "q", 5, List.of("t")).isEmpty());
        assertThrows(
                VikingDataPlaneException.class,
                () ->
                        client.addSession(
                                "c",
                                null,
                                List.of(new Message("user", "x")),
                                new Metadata("u", "a", 1L)));
    }

    @Test
    void transportParseAndShapeFailuresAreWrapped() {
        FakeTransport transport = new FakeTransport();
        VikingMemoryApiKeyClient client =
                new VikingMemoryApiKeyClient("secret", "https://example.com", "p", transport);
        transport.failure = new IllegalStateException("offline");
        assertThrows(
                VikingDataPlaneException.class,
                () -> client.searchMemory("c", "u", "q", 1, List.of("t")));
        transport.failure = null;
        transport.response = new VikingMemoryApiKeyClient.Response(200, "not-json", "log");
        assertEquals(
                "log",
                assertThrows(
                                VikingDataPlaneException.class,
                                () -> client.searchMemory("c", "u", "q", 1, List.of("t")))
                        .getRequestId());
        transport.response =
                new VikingMemoryApiKeyClient.Response(
                        200, "{\"code\":0,\"data\":{\"result_list\":{}}}", null);
        assertThrows(
                VikingDataPlaneException.class,
                () -> client.searchMemory("c", "u", "q", 1, List.of("t")));
    }

    @Test
    void publicClientUsesBearerHttpTransport() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                VikingMemoryApiKeyClient.SEARCH_PATH,
                exchange -> {
                    assertEquals(
                            "Bearer key", exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] response = "{\"code\":0,\"data\":{}}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("X-Tt-Logid", "http-id");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            VikingMemoryApiKeyClient client =
                    new VikingMemoryApiKeyClient(
                            "key", "http://localhost:" + server.getAddress().getPort(), "p");
            assertTrue(client.searchMemory("c", "u", "q", 1, List.of("t")).isEmpty());
        } finally {
            server.stop(0);
        }
    }

    private static final class FakeTransport implements VikingMemoryApiKeyClient.Transport {
        String url, apiKey, body;
        VikingMemoryApiKeyClient.Response response;
        RuntimeException failure;

        public VikingMemoryApiKeyClient.Response post(String url, String apiKey, String body) {
            if (failure != null) throw failure;
            this.url = url;
            this.apiKey = apiKey;
            this.body = body;
            return response;
        }
    }
}
