/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.volcengine.veadk.memory.mem0;

import com.fasterxml.jackson.databind.JsonNode;
import com.volcengine.veadk.utils.JSONUtil;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

public class Mem0RuntimeClient {

    static final String ADD_MEMORY_PATH = "/v1/memories/";
    static final String SEARCH_MEMORY_PATH = "/v2/memories/search/";
    private static final String OUTPUT_FORMAT = "v1.1";

    private final String baseUrl;
    private final String apiKey;
    private final RuntimeHttpTransport transport;

    public Mem0RuntimeClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, new JdkHttpTransport());
    }

    Mem0RuntimeClient(String baseUrl, String apiKey, RuntimeHttpTransport transport) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.transport = transport;
        if (StringUtils.isBlank(this.baseUrl)) {
            throw new IllegalArgumentException("Mem0 baseUrl must not be blank.");
        }
        if (StringUtils.isBlank(this.apiKey)) {
            throw new IllegalArgumentException("Mem0 apiKey must not be blank.");
        }
    }

    public boolean addMemory(String userId, List<Mem0Message> messages) throws IOException {
        return addMemory(userId, messages, true);
    }

    public boolean addMemory(String userId, List<Mem0Message> messages, boolean asyncMode)
            throws IOException {
        if (messages == null || messages.isEmpty()) {
            return true;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", messages);
        body.put("user_id", userId);
        body.put("async_mode", asyncMode);
        body.put("output_format", OUTPUT_FORMAT);

        RuntimeHttpResponse response = post(ADD_MEMORY_PATH, body);
        return response.isSuccess();
    }

    public List<Mem0MemoryResult> searchMemory(String userId, String query, int topK)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("user_id", userId);
        body.put("top_k", topK);
        body.put("output_format", OUTPUT_FORMAT);

        RuntimeHttpResponse response = post(SEARCH_MEMORY_PATH, body);
        if (!response.isSuccess()) {
            return Collections.emptyList();
        }
        return parseSearchResults(response.body());
    }

    private RuntimeHttpResponse post(String path, Object body) throws IOException {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Token " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(JSONUtil.toJson(body)))
                        .build();
        try {
            return transport.send(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling Mem0 runtime API.", e);
        }
    }

    static String normalizeBaseUrl(String baseUrl) {
        String normalized = StringUtils.trimToNull(baseUrl);
        if (normalized == null) {
            return null;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1") || normalized.endsWith("/v2")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    static List<Mem0MemoryResult> parseSearchResults(String responseBody) throws IOException {
        if (StringUtils.isBlank(responseBody)) {
            return Collections.emptyList();
        }

        JsonNode root = JSONUtil.parseJson(responseBody);
        JsonNode resultNode = root.isArray() ? root : root.path("results");
        if (!resultNode.isArray()) {
            return Collections.emptyList();
        }

        List<Mem0MemoryResult> results = new ArrayList<>();
        for (JsonNode node : resultNode) {
            String memory = extractMemoryText(node);
            if (StringUtils.isBlank(memory)) {
                continue;
            }
            JsonNode scoreNode = node.path("score");
            Double score = scoreNode.isNumber() ? scoreNode.asDouble() : null;
            results.add(new Mem0MemoryResult(memory, score));
        }
        return results;
    }

    private static String extractMemoryText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        for (String field : List.of("memory", "text", "summary", "content")) {
            JsonNode value = node.path(field);
            if (value.isTextual()) {
                return value.asText();
            }
        }
        return null;
    }

    interface RuntimeHttpTransport {
        RuntimeHttpResponse send(HttpRequest request) throws IOException, InterruptedException;
    }

    static class RuntimeHttpResponse {
        private final int statusCode;
        private final String body;

        RuntimeHttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        String body() {
            return body;
        }
    }

    private static class JdkHttpTransport implements RuntimeHttpTransport {
        private final HttpClient httpClient;

        JdkHttpTransport() {
            this.httpClient = HttpClient.newHttpClient();
        }

        @Override
        public RuntimeHttpResponse send(HttpRequest request)
                throws IOException, InterruptedException {
            java.net.http.HttpResponse<String> response =
                    httpClient.send(request, BodyHandlers.ofString());
            return new RuntimeHttpResponse(response.statusCode(), response.body());
        }
    }
}
