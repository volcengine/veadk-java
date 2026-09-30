/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates. */
package com.volcengine.veadk.integration.vikingknowledgebase;

import com.fasterxml.jackson.databind.JsonNode;
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

public final class VikingKnowledgebaseApiKeyClient {
    static final String SEARCH_PATH = "/api/knowledge/collection/search_knowledge";
    private final String apiKey;
    private final String baseUrl;
    private final String project;
    private final String resourceId;
    private final Transport transport;

    public VikingKnowledgebaseApiKeyClient(
            String apiKey, String baseUrl, String project, String resourceId) {
        this(apiKey, baseUrl, project, resourceId, new JdkTransport());
    }

    VikingKnowledgebaseApiKeyClient(
            String apiKey, String baseUrl, String project, String resourceId, Transport transport) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.project = project;
        this.resourceId = resourceId;
        this.transport = transport;
    }

    public List<KnowledgebaseEntry> searchKnowledge(
            String collectionName,
            String query,
            int topK,
            Map<String, String> metadata,
            boolean rerank,
            int chunkDiffusionCount) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", collectionName);
        body.put("project", project);
        body.put("query", query);
        body.put("limit", topK);
        body.put("dense_weight", 0.5);
        if (resourceId != null) body.put("resource_id", resourceId);
        if (metadata != null && !metadata.isEmpty()) {
            Map<String, Object> filter = new HashMap<>();
            filter.put("op", "and");
            List<Map<String, Object>> conditions = new ArrayList<>();
            metadata.forEach(
                    (key, value) ->
                            conditions.add(
                                    Map.of("op", "must", "field", key, "conds", List.of(value))));
            filter.put("conds", conditions);
            body.put("query_param", Map.of("doc_filter", filter));
        }
        body.put(
                "post_processing",
                Map.of("rerank_switch", rerank, "chunk_diffusion_count", chunkDiffusionCount));
        Response response;
        try {
            response = transport.post(baseUrl + SEARCH_PATH, apiKey, JSONUtil.toJson(body));
        } catch (Exception e) {
            throw failure(null, "unknown", null, e);
        }
        JsonNode root;
        try {
            root = JSONUtil.parseJson(response.body());
        } catch (Exception e) {
            throw failure(null, headerRequestId(response), response.statusCode(), e);
        }
        String requestId = requestId(root, response);
        JsonNode code = root.get("code");
        if (response.statusCode() != 200
                || code == null
                || !code.isIntegralNumber()
                || code.intValue() != 0)
            throw failure(
                    code != null && code.isIntegralNumber() ? code.intValue() : null,
                    requestId,
                    response.statusCode(),
                    null);
        JsonNode results = root.path("data").path("result_list");
        if (results.isMissingNode() || results.isNull()) return List.of();
        if (!results.isArray()) throw failure(0, requestId, response.statusCode(), null);
        List<KnowledgebaseEntry> entries = new ArrayList<>();
        for (JsonNode item : results) {
            Map<String, String> values = new HashMap<>();
            JsonNode raw = item.path("doc_info").path("doc_meta");
            try {
                JsonNode metas = raw.isTextual() ? JSONUtil.parseJson(raw.asText()) : raw;
                if (metas.isArray())
                    for (JsonNode meta : metas)
                        values.put(
                                meta.path("field_name").asText(),
                                meta.path("field_value").asText());
            } catch (Exception e) {
                throw failure(0, requestId, response.statusCode(), e);
            }
            entries.add(new KnowledgebaseEntry(item.path("content").asText(""), values));
        }
        return entries;
    }

    private VikingDataPlaneException failure(
            Integer code, String requestId, Integer status, Throwable cause) {
        return new VikingDataPlaneException("SearchKnowledge", code, requestId, status, cause);
    }

    private static String requestId(JsonNode root, Response response) {
        String id = root.path("request_id").asText(null);
        if (id == null) id = root.path("ResponseMetadata").path("RequestId").asText(null);
        return id == null ? headerRequestId(response) : id;
    }

    private static String headerRequestId(Response response) {
        return response.requestId() == null ? "unknown" : response.requestId();
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
