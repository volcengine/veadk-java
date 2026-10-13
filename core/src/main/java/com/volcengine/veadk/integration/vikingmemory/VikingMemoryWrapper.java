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
package com.volcengine.veadk.integration.vikingmemory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.adk.memory.MemoryEntry;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.volcengine.error.SdkError;
import com.volcengine.helper.Const;
import com.volcengine.model.ApiInfo;
import com.volcengine.model.Credentials;
import com.volcengine.model.ServiceInfo;
import com.volcengine.model.response.RawResponse;
import com.volcengine.service.BaseServiceImpl;
import com.volcengine.veadk.utils.EnvUtil;
import com.volcengine.veadk.utils.JSONUtil;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VikingMemoryWrapper extends BaseServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(VikingMemoryWrapper.class);
    private static final String BASE_URL = "https://api-knowledgebase.mlp.cn-beijing.volces.com";
    private static final String SEARCH_MEMORY_PATH = "/api/memory/search";
    private static final String ADD_SESSION_PATH = "/api/memory/session/add";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    private final String apiKey;
    private final VikingHttpTransport transport;

    private static ServiceInfo SERVICE_INFO =
            new ServiceInfo(
                    new HashMap<String, Object>() {
                        {
                            put(Const.CONNECTION_TIMEOUT, 5000);
                            put(Const.SOCKET_TIMEOUT, 5000);
                            put(Const.Host, "api-knowledgebase.mlp.cn-beijing.volces.com");
                            put(
                                    Const.Header,
                                    new ArrayList<Header>() {
                                        {
                                            add(new BasicHeader("Accept", "application/json"));
                                        }
                                    });
                            put(Const.Credentials, new Credentials("cn-beijing", "air"));
                        }
                    });

    private static Map<String, ApiInfo> API_INFO_LIST =
            new HashMap<String, ApiInfo>() {
                {
                    put(
                            "GetCollection",
                            new ApiInfo(
                                    new HashMap<String, Object>() {
                                        {
                                            put(Const.Method, "POST");
                                            put(Const.Path, "/api/memory/collection/info");
                                            put(
                                                    Const.Header,
                                                    Arrays.asList(
                                                            new BasicHeader(
                                                                    "Accept", "application/json"),
                                                            new BasicHeader(
                                                                    "Content-Type",
                                                                    "application/json")));
                                        }
                                    }));
                    put(
                            "CreateCollection",
                            new ApiInfo(
                                    new HashMap<String, Object>() {
                                        {
                                            put(Const.Method, "POST");
                                            put(Const.Path, "/api/memory/collection/create");
                                            put(
                                                    Const.Header,
                                                    Arrays.asList(
                                                            new BasicHeader(
                                                                    "Accept", "application/json"),
                                                            new BasicHeader(
                                                                    "Content-Type",
                                                                    "application/json")));
                                        }
                                    }));
                    put(
                            "SearchMemory",
                            new ApiInfo(
                                    new HashMap<String, Object>() {
                                        {
                                            put(Const.Method, "POST");
                                            put(Const.Path, "/api/memory/search");
                                            put(
                                                    Const.Header,
                                                    Arrays.asList(
                                                            new BasicHeader(
                                                                    "Accept", "application/json"),
                                                            new BasicHeader(
                                                                    "Content-Type",
                                                                    "application/json")));
                                        }
                                    }));
                    put(
                            "AddSession",
                            new ApiInfo(
                                    new HashMap<String, Object>() {
                                        {
                                            put(Const.Method, "POST");
                                            put(Const.Path, "/api/memory/session/add");
                                            put(
                                                    Const.Header,
                                                    Arrays.asList(
                                                            new BasicHeader(
                                                                    "Accept", "application/json"),
                                                            new BasicHeader(
                                                                    "Content-Type",
                                                                    "application/json")));
                                        }
                                    }));
                }
            };

    public VikingMemoryWrapper(String accessKey, String secretKey) {
        this(accessKey, secretKey, null);
    }

    public VikingMemoryWrapper(String accessKey, String secretKey, String apiKey) {
        this(accessKey, secretKey, apiKey, new JdkVikingHttpTransport());
    }

    VikingMemoryWrapper(
            String accessKey, String secretKey, String apiKey, VikingHttpTransport transport) {
        super(SERVICE_INFO, API_INFO_LIST);
        if (StringUtils.isNotBlank(accessKey)) {
            setAccessKey(accessKey);
        }
        if (StringUtils.isNotBlank(secretKey)) {
            setSecretKey(secretKey);
        }
        this.apiKey = EnvUtil.sanitizeApiKey(apiKey);
        this.transport = transport;
    }

    public boolean isCollectionExists(String collectionName) {
        try {
            Map<String, String> body = new HashMap<>();
            body.put("CollectionName", collectionName);
            String bodyStr = JSONUtil.toJson(body);
            RawResponse response = json("GetCollection", null, bodyStr);
            if (response.getCode() != SdkError.SUCCESS.getNumber()) {
                // java.lang.Exception:
                // {"ResponseMetadata":{"Action":"","Error":{"Code":"InvalidParameter","Message":"A
                // parameter specified in the request is not valid: collection not
                // exist"},"Region":"cn-beijing","RequestId":"2cb19c911c620b9d97477f7236c7cc3a","Service":"knowledge_base_server","Version":""}}
                log.error(
                        "GetCollection request:{}, raw response:{}",
                        bodyStr,
                        response.getException().getMessage());
                return false;
            }

            log.debug(
                    "GetCollection request:{}, raw response:{}",
                    bodyStr,
                    JSONUtil.parseJson(response.getData()));

            JsonNode rootNode = JSONUtil.parseJson(response.getData());
            JsonNode name = rootNode.path("Result").path("Name");
            return !name.isMissingNode() && !name.isNull();
        } catch (IOException e) {
            log.error("isCollectionExists failed, collectionName:" + collectionName, e);
            return false;
        }
    }

    public boolean createCollection(String collectionName, List<String> builtinEventTypes) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("CollectionName", collectionName);
            body.put("Description", "Created by Volcengine Agent Development Kit VeADK");
            body.put("BuiltinEventTypes", builtinEventTypes);

            String bodyStr = JSONUtil.toJson(body);
            RawResponse response = json("CreateCollection", null, bodyStr);
            if (response.getCode() != SdkError.SUCCESS.getNumber()) {
                log.error(
                        "CreateCollection request:{}, raw response:{}",
                        bodyStr,
                        response.getException());
                return false;
            }

            log.debug(
                    "CreateCollection request:{}, raw response:{}",
                    bodyStr,
                    JSONUtil.parseJson(response.getData()));

            JsonNode rootNode = JSONUtil.parseJson(response.getData());
            JsonNode resourceId = rootNode.path("Result").path("ResourceId");
            return !resourceId.isMissingNode() && !resourceId.isNull();
        } catch (IOException e) {
            log.error("createCollection failed, collectionName:" + collectionName, e);
            return false;
        }
    }

    public boolean addSession(String collectionName, List<Message> messages, Metadata metadata)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("collection_name", collectionName);
        body.put("messages", messages);
        body.put("metadata", metadata);

        String bodyStr = JSONUtil.toJson(body);

        VikingHttpResponse response =
                StringUtils.isNotBlank(apiKey)
                        ? postWithApiKey(ADD_SESSION_PATH, bodyStr)
                        : postWithAkSk("AddSession", bodyStr);
        if (!response.isSuccess()) {
            log.warn(
                    "AddSession failed, path:{}, status:{}",
                    ADD_SESSION_PATH,
                    response.statusCode());
            return false;
        }
        log.debug(
                "AddSession request:{}, raw response:{}",
                bodyStr,
                JSONUtil.parseJson(response.body()));

        JsonNode rootNode = JSONUtil.parseJson(response.body());
        JsonNode sessionIdNode = rootNode.path("data").path("session_id");
        return !sessionIdNode.isMissingNode() && !sessionIdNode.isNull();
    }

    public List<MemoryEntry> searchMemory(
            String collectionName, String userId, String query, int topK, List<String> eventTypes)
            throws Exception {
        Map<String, Object> filter = new HashMap<>();
        filter.put("user_id", userId);
        filter.put("memory_type", eventTypes);

        Map<String, Object> body = new HashMap<>();
        body.put("collection_name", collectionName);
        body.put("query", query);
        body.put("filter", filter);
        body.put("limit", topK);

        String bodyStr = JSONUtil.toJson(body);

        VikingHttpResponse response =
                StringUtils.isNotBlank(apiKey)
                        ? postWithApiKey(SEARCH_MEMORY_PATH, bodyStr)
                        : postWithAkSk("SearchMemory", bodyStr);
        if (!response.isSuccess()) {
            log.warn(
                    "SearchMemory failed, path:{}, status:{}",
                    SEARCH_MEMORY_PATH,
                    response.statusCode());
            return Collections.emptyList();
        }
        log.debug(
                "SearchMemory request:{}, raw response:{}",
                bodyStr,
                JSONUtil.parseJson(response.body()));

        JsonNode rootNode = JSONUtil.parseJson(response.body());
        JsonNode resultList = rootNode.path("data").path("result_list");
        List<MemoryEntry> memoryEntries = new ArrayList<>();

        if (!resultList.isMissingNode() && !resultList.isNull() && resultList.isArray()) {
            for (JsonNode resultNode : resultList) {
                JsonNode summaryNode = resultNode.path("memory_info").path("summary");
                if (!summaryNode.isMissingNode() && !summaryNode.isNull()) {
                    memoryEntries.add(buildMemoryEntry("user", summaryNode.asText()));
                }
            }
        }

        return memoryEntries;
    }

    private VikingHttpResponse postWithAkSk(String action, String bodyStr) throws Exception {
        RawResponse response = json(action, null, bodyStr);
        String body = response.getData() == null ? "" : new String(response.getData());
        return new VikingHttpResponse(response.getCode(), body);
    }

    private VikingHttpResponse postWithApiKey(String path, String bodyStr) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(BASE_URL + path))
                        .timeout(HTTP_TIMEOUT)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                        .build();
        try {
            return transport.send(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling Viking Memory API.", e);
        }
    }

    private MemoryEntry buildMemoryEntry(String role, String text) {
        return MemoryEntry.builder()
                .author(role)
                .content(
                        Content.builder()
                                .role(role)
                                .parts(Collections.singletonList(Part.builder().text(text).build()))
                                .build())
                .build();
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

        boolean isSuccess() {
            return statusCode == SdkError.SUCCESS.getNumber()
                    || (statusCode >= 200 && statusCode < 300);
        }

        int statusCode() {
            return statusCode;
        }

        String body() {
            return body;
        }
    }

    private static class JdkVikingHttpTransport implements VikingHttpTransport {
        private final HttpClient httpClient;

        JdkVikingHttpTransport() {
            this.httpClient = HttpClient.newHttpClient();
        }

        @Override
        public VikingHttpResponse send(HttpRequest request)
                throws IOException, InterruptedException {
            java.net.http.HttpResponse<String> response =
                    httpClient.send(request, BodyHandlers.ofString());
            return new VikingHttpResponse(response.statusCode(), response.body());
        }
    }
}
