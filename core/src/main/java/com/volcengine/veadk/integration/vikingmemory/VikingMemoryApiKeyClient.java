/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates. */
package com.volcengine.veadk.integration.vikingmemory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.adk.memory.MemoryEntry;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.volcengine.veadk.integration.viking.VikingDataPlaneException;
import com.volcengine.veadk.utils.JSONUtil;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class VikingMemoryApiKeyClient {
    static final String ADD_PATH = "/api/memory/session/add";
    static final String SEARCH_PATH = "/api/memory/search";
    private final String apiKey, baseUrl, project;
    private final Transport transport;

    public VikingMemoryApiKeyClient(String apiKey, String baseUrl, String project) {
        this(apiKey, baseUrl, project, new JdkTransport());
    }

    VikingMemoryApiKeyClient(String apiKey, String baseUrl, String project, Transport transport) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.project = project;
        this.transport = transport;
    }

    public boolean addSession(
            String collectionName, String sessionId, List<Message> messages, Metadata metadata) {
        Map<String, Object> body = new HashMap<>();
        body.put("collection_name", collectionName);
        body.put("project_name", project);
        if (sessionId != null) body.put("session_id", sessionId);
        body.put("messages", messages);
        body.put("metadata", metadata);
        JsonNode root = request("AddSession", ADD_PATH, body);
        if (root.path("data").path("session_id").asText("").isBlank())
            throw failure("AddSession", 0, requestId(root, null), 200, null);
        return true;
    }

    public List<MemoryEntry> searchMemory(
            String collectionName,
            String userId,
            String query,
            int topK,
            List<String> memoryTypes) {
        Map<String, Object> body = new HashMap<>();
        body.put("collection_name", collectionName);
        body.put("project_name", project);
        body.put("query", query);
        body.put("filter", Map.of("user_id", userId, "memory_type", memoryTypes));
        body.put("limit", topK);
        JsonNode root = request("SearchMemory", SEARCH_PATH, body);
        JsonNode results = root.path("data").path("result_list");
        if (results.isMissingNode() || results.isNull()) return List.of();
        if (!results.isArray()) throw failure("SearchMemory", 0, requestId(root, null), 200, null);
        List<MemoryEntry> entries = new ArrayList<>();
        for (JsonNode item : results) {
            JsonNode summary = item.path("memory_info").path("summary");
            if (!summary.isMissingNode() && !summary.isNull() && !summary.asText().isEmpty()) {
                entries.add(
                        MemoryEntry.builder()
                                .author("user")
                                .content(
                                        Content.builder()
                                                .role("user")
                                                .parts(
                                                        List.of(
                                                                Part.builder()
                                                                        .text(summary.asText())
                                                                        .build()))
                                                .build())
                                .build());
            }
        }
        return entries;
    }

    private JsonNode request(String operation, String path, Object body) {
        Response response;
        try {
            response = transport.post(baseUrl + path, apiKey, JSONUtil.toJson(body));
        } catch (Exception e) {
            throw failure(operation, null, "unknown", null, e);
        }
        JsonNode root;
        try {
            root = JSONUtil.parseJson(response.body());
        } catch (Exception e) {
            throw failure(operation, null, headerRequestId(response), response.statusCode(), e);
        }
        String requestId = requestId(root, response);
        JsonNode code = root.get("code");
        if (response.statusCode() != 200
                || code == null
                || !code.isIntegralNumber()
                || code.intValue() != 0)
            throw failure(
                    operation,
                    code != null && code.isIntegralNumber() ? code.intValue() : null,
                    requestId,
                    response.statusCode(),
                    null);
        return root;
    }

    private static VikingDataPlaneException failure(
            String operation, Integer code, String requestId, Integer status, Throwable cause) {
        return new VikingDataPlaneException(operation, code, requestId, status, cause);
    }

    private static String requestId(JsonNode root, Response response) {
        String id = root.path("request_id").asText(null);
        if (id == null) id = root.path("ResponseMetadata").path("RequestId").asText(null);
        return id == null ? headerRequestId(response) : id;
    }

    private static String headerRequestId(Response response) {
        return response == null || response.requestId() == null ? "unknown" : response.requestId();
    }

    interface Transport {
        Response post(String url, String apiKey, String body) throws Exception;
    }

    record Response(int statusCode, String body, String requestId) {}

    private static final class JdkTransport implements Transport {
        private final HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        public Response post(String url, String apiKey, String body) throws Exception {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(5))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(
                    response.statusCode(),
                    response.body(),
                    response.headers().firstValue("X-Tt-Logid").orElse(null));
        }
    }
}
