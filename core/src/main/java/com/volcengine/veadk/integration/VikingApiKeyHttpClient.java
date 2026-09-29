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
package com.volcengine.veadk.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volcengine.veadk.utils.JSONUtil;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

public class VikingApiKeyHttpClient {

    public static final String KNOWLEDGEBASE_SEARCH_PATH =
            "/api/knowledge/collection/search_knowledge";
    public static final String MEMORY_ADD_PATH = "/api/memory/session/add";
    public static final String MEMORY_SEARCH_PATH = "/api/memory/search";

    private static final String BASE_URL = "https://api-knowledgebase.mlp.cn-beijing.volces.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_PATHS =
            Set.of(KNOWLEDGEBASE_SEARCH_PATH, MEMORY_ADD_PATH, MEMORY_SEARCH_PATH);

    private final String apiKey;
    private final VikingHttpTransport transport;

    public VikingApiKeyHttpClient(String apiKey) {
        this(apiKey, new JdkHttpTransport());
    }

    VikingApiKeyHttpClient(String apiKey, VikingHttpTransport transport) {
        this.apiKey = StringUtils.trimToNull(apiKey);
        this.transport = transport;
        if (this.apiKey == null) {
            throw new IllegalArgumentException("Viking apiKey must not be blank.");
        }
    }

    public JsonNode post(String operation, String path, Object body) {
        if (!ALLOWED_PATHS.contains(path)) {
            throw new IllegalArgumentException("Unsupported Viking API Key path: " + path);
        }

        HttpRequest request =
                HttpRequest.newBuilder(URI.create(BASE_URL + path))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(JSONUtil.toJson(body)))
                        .build();
        VikingHttpResponse response;
        try {
            response = transport.send(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(operation, path, null, null, null, "request interrupted", e);
        } catch (IOException e) {
            throw failure(operation, path, null, null, null, "request failed", e);
        }

        JsonNode root = parseResponse(operation, path, response);
        JsonNode code = root.path("code");
        if (!code.isMissingNode() && !code.isNull() && !isSuccessCode(code)) {
            throw failure(
                    operation,
                    path,
                    response.statusCode(),
                    code.asText(),
                    requestId(root),
                    "service rejected request",
                    null);
        }
        return root;
    }

    public static IllegalStateException invalidResponse(
            String operation, String path, JsonNode root) {
        return failure(
                operation,
                path,
                null,
                null,
                root == null ? null : requestId(root),
                "invalid response structure",
                null);
    }

    private static JsonNode parseResponse(
            String operation, String path, VikingHttpResponse response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            JsonNode root = tryParse(response.body());
            throw failure(
                    operation,
                    path,
                    response.statusCode(),
                    root == null ? null : businessCode(root),
                    root == null ? null : requestId(root),
                    "HTTP request failed",
                    null);
        }
        if (StringUtils.isBlank(response.body())) {
            throw failure(
                    operation, path, response.statusCode(), null, null, "empty response", null);
        }
        try {
            JsonNode root = MAPPER.readTree(response.body());
            if (root == null || !root.isObject()) {
                throw failure(
                        operation,
                        path,
                        response.statusCode(),
                        null,
                        null,
                        "invalid response structure",
                        null);
            }
            return root;
        } catch (IOException e) {
            throw failure(
                    operation,
                    path,
                    response.statusCode(),
                    null,
                    null,
                    "response parsing failed",
                    e);
        }
    }

    private static JsonNode tryParse(String body) {
        if (StringUtils.isBlank(body)) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean isSuccessCode(JsonNode code) {
        return code.isNumber() ? code.asInt() == 0 : "0".equals(code.asText());
    }

    private static String businessCode(JsonNode root) {
        JsonNode code = root.path("code");
        return code.isMissingNode() || code.isNull() ? null : code.asText();
    }

    private static String requestId(JsonNode root) {
        for (String field : Set.of("request_id", "requestId", "RequestId")) {
            JsonNode value = root.path(field);
            if (value.isTextual() && StringUtils.isNotBlank(value.asText())) {
                return value.asText();
            }
        }
        return null;
    }

    private static IllegalStateException failure(
            String operation,
            String path,
            Integer statusCode,
            String businessCode,
            String requestId,
            String reason,
            Throwable cause) {
        StringBuilder message =
                new StringBuilder("Viking API Key operation failed: operation=")
                        .append(operation)
                        .append(", path=")
                        .append(path)
                        .append(", reason=")
                        .append(reason);
        if (statusCode != null) {
            message.append(", status=").append(statusCode);
        }
        String safeBusinessCode = safeMetadata(businessCode);
        if (safeBusinessCode != null) {
            message.append(", code=").append(safeBusinessCode);
        }
        String safeRequestId = safeMetadata(requestId);
        if (safeRequestId != null) {
            message.append(", requestId=").append(safeRequestId);
        }
        return new IllegalStateException(message.toString());
    }

    private static String safeMetadata(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
            return null;
        }
        return value;
    }

    interface VikingHttpTransport {
        VikingHttpResponse send(HttpRequest request) throws IOException, InterruptedException;
    }

    static class VikingHttpResponse {
        private final int statusCode;
        private final String body;

        VikingHttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        int statusCode() {
            return statusCode;
        }

        String body() {
            return body;
        }
    }

    private static class JdkHttpTransport implements VikingHttpTransport {
        private final HttpClient httpClient = HttpClient.newHttpClient();

        @Override
        public VikingHttpResponse send(HttpRequest request)
                throws IOException, InterruptedException {
            java.net.http.HttpResponse<String> response =
                    httpClient.send(request, BodyHandlers.ofString());
            return new VikingHttpResponse(response.statusCode(), response.body());
        }
    }
}
