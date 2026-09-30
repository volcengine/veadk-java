package com.volcengine.veadk.integration.vikingknowledgebase;

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
import java.util.Map;
import org.junit.jupiter.api.Test;

class VikingKnowledgebaseApiKeyClientTest {
    @Test
    void searchSendsExpectedContractAndMapsResult() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.response =
                new VikingKnowledgebaseApiKeyClient.Response(
                        200,
                        "{\"code\":0,\"data\":{\"result_list\":[{\"content\":\"answer\",\"doc_info\":{\"doc_meta\":\"[{\\\"field_name\\\":\\\"k\\\",\\\"field_value\\\":\\\"v\\\"}]\"}}]}}",
                        "rid");
        VikingKnowledgebaseApiKeyClient client =
                new VikingKnowledgebaseApiKeyClient(
                        "secret", "https://example.com", "p", "resource", transport);
        List<KnowledgebaseEntry> entries =
                client.searchKnowledge("c", "q", 3, Map.of("tag", "x"), true, 2);
        assertEquals("answer", entries.get(0).getContent());
        assertEquals("v", entries.get(0).getMetadata().get("k"));
        JsonNode body = JSONUtil.parseJson(transport.body);
        assertEquals("p", body.path("project").asText());
        assertEquals("resource", body.path("resource_id").asText());
        assertEquals("and", body.path("query_param").path("doc_filter").path("op").asText());
    }

    @Test
    void errorsThrowWithoutSecret() {
        FakeTransport transport = new FakeTransport();
        transport.response =
                new VikingKnowledgebaseApiKeyClient.Response(
                        401, "{\"code\":7,\"ResponseMetadata\":{\"RequestId\":\"old-id\"}}", null);
        VikingKnowledgebaseApiKeyClient client =
                new VikingKnowledgebaseApiKeyClient(
                        "unique-secret", "https://example.com", "p", null, transport);
        VikingDataPlaneException error =
                assertThrows(
                        VikingDataPlaneException.class,
                        () -> client.searchKnowledge("c", "q", 1, null, false, 0));
        assertEquals("old-id", error.getRequestId());
        assertFalse(error.toString().contains("unique-secret"));
    }

    @Test
    void transportParseAndShapeFailuresAreWrapped() {
        FakeTransport transport = new FakeTransport();
        transport.failure = new IllegalStateException("offline");
        VikingKnowledgebaseApiKeyClient client = client(transport);
        assertThrows(
                VikingDataPlaneException.class,
                () -> client.searchKnowledge("c", "q", 1, null, false, 0));

        transport.failure = null;
        transport.response = new VikingKnowledgebaseApiKeyClient.Response(200, "not-json", "log");
        assertEquals(
                "log",
                assertThrows(
                                VikingDataPlaneException.class,
                                () -> client.searchKnowledge("c", "q", 1, null, false, 0))
                        .getRequestId());

        transport.response =
                new VikingKnowledgebaseApiKeyClient.Response(
                        200, "{\"code\":0,\"data\":{\"result_list\":{}}}", null);
        assertThrows(
                VikingDataPlaneException.class,
                () -> client.searchKnowledge("c", "q", 1, null, false, 0));
        transport.response =
                new VikingKnowledgebaseApiKeyClient.Response(
                        200,
                        "{\"code\":0,\"data\":{\"result_list\":[{\"doc_info\":{\"doc_meta\":\"bad\"}}]}}",
                        null);
        assertThrows(
                VikingDataPlaneException.class,
                () -> client.searchKnowledge("c", "q", 1, null, false, 0));
    }

    @Test
    void publicClientUsesBearerHttpTransport() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                VikingKnowledgebaseApiKeyClient.SEARCH_PATH,
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
            VikingKnowledgebaseApiKeyClient client =
                    new VikingKnowledgebaseApiKeyClient(
                            "key", "http://localhost:" + server.getAddress().getPort(), "p", null);
            assertTrue(client.searchKnowledge("c", "q", 1, null, false, 0).isEmpty());
        } finally {
            server.stop(0);
        }
    }

    private static VikingKnowledgebaseApiKeyClient client(FakeTransport transport) {
        return new VikingKnowledgebaseApiKeyClient(
                "secret", "https://example.com", "p", null, transport);
    }

    private static final class FakeTransport implements VikingKnowledgebaseApiKeyClient.Transport {
        String body;
        VikingKnowledgebaseApiKeyClient.Response response;
        RuntimeException failure;

        public VikingKnowledgebaseApiKeyClient.Response post(
                String url, String apiKey, String body) {
            if (failure != null) throw failure;
            this.body = body;
            return response;
        }
    }
}
