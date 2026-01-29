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
package com.volcengine.veadk.integration.vikingknowledgebase;

import com.fasterxml.jackson.databind.JsonNode;
import com.volcengine.error.SdkError;
import com.volcengine.helper.Const;
import com.volcengine.model.ApiInfo;
import com.volcengine.model.Credentials;
import com.volcengine.model.ServiceInfo;
import com.volcengine.model.response.RawResponse;
import com.volcengine.service.BaseServiceImpl;
import com.volcengine.veadk.utils.JSONUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VikingKnowledgebaseWrapper extends BaseServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(VikingKnowledgebaseWrapper.class);

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
                                            put(Const.Path, "/api/knowledge/collection/info");
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
                                            put(Const.Path, "/api/knowledge/collection/create");
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
                            "AddDoc",
                            new ApiInfo(
                                    new HashMap<String, Object>() {
                                        {
                                            put(Const.Method, "POST");
                                            put(Const.Path, "/api/knowledge/doc/add");
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
                            "SearchKnowledge",
                            new ApiInfo(
                                    new HashMap<String, Object>() {
                                        {
                                            put(Const.Method, "POST");
                                            put(
                                                    Const.Path,
                                                    "/api/knowledge/collection/search_knowledge");
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

    public VikingKnowledgebaseWrapper(String accessKey, String secretKey) {
        super(SERVICE_INFO, API_INFO_LIST);
        setAccessKey(accessKey);
        setSecretKey(secretKey);
    }

    public boolean isCollectionExists(String collectionName) {
        try {
            Map<String, String> body = new HashMap<>();
            body.put("name", collectionName);
            String bodyStr = JSONUtil.toJson(body);
            RawResponse response = json("GetCollection", null, bodyStr);
            if (response.getCode() != SdkError.SUCCESS.getNumber()) {
                // java.lang.Exception: {"code":1000005,"message":"collection not
                // exist","request_id":"02176500905802200000000000000000000ffffac130a31e8d8af"}
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
            JsonNode resourceId = rootNode.path("data").path("resource_id");
            return !resourceId.isMissingNode() && !resourceId.isNull();
        } catch (IOException e) {
            log.error("isCollectionExists failed, collectionName:" + collectionName, e);
            return false;
        }
    }

    public boolean createCollection(String collectionName) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("name", collectionName);
            body.put("description", "Created by Volcengine Agent Development Kit VeADK");

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
            JsonNode resourceId = rootNode.path("data").path("resource_id");
            return !resourceId.isMissingNode() && !resourceId.isNull();
        } catch (IOException e) {
            log.error("createCollection failed, collectionName:" + collectionName, e);
            return false;
        }
    }

    public boolean addDoc(String collectionName, String tosUrl) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("collection_name", collectionName);
            body.put("add_type", "tos");
            body.put("tos_path", tosUrl);

            String bodyStr = JSONUtil.toJson(body);
            RawResponse response = json("AddDoc", null, bodyStr);

            if (response.getCode() != SdkError.SUCCESS.getNumber()) {
                log.error("AddDoc request:{}, raw response:{}", bodyStr, response.getException());
                return false;
            }
            log.debug(
                    "AddDoc request:{}, raw response:{}",
                    bodyStr,
                    JSONUtil.parseJson(response.getData()));

            JsonNode rootNode = JSONUtil.parseJson(response.getData());
            JsonNode docId = rootNode.path("data").path("doc_id");

            return !docId.isMissingNode() && !docId.isNull();
        } catch (IOException e) {
            log.error("addDoc failed", e);
            return false;
        }
    }

    public List<KnowledgebaseEntry> searchKnowledge(
            String collectionName,
            String query,
            int topK,
            Map<String, String> metadata,
            boolean rerank,
            int chunkDiffusionCount) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("name", collectionName);
            body.put("query", query);
            body.put("limit", topK);

            if (metadata != null && !metadata.isEmpty()) {
                Map<String, Object> queryParam = new HashMap<>();
                queryParam.put("op", "and");
                List<Map<String, Object>> conds = new ArrayList<>();
                for (Map.Entry<String, String> entry : metadata.entrySet()) {
                    Map<String, Object> cond = new HashMap<>();
                    cond.put("op", "must");
                    cond.put("field", entry.getKey());
                    cond.put("conds", Collections.singletonList(entry.getValue()));
                    conds.add(cond);
                }
                queryParam.put("conds", conds);
                body.put("query_param", queryParam);
            }

            Map<String, Object> postProcessing = new HashMap<>();
            postProcessing.put("rerank_switch", rerank);
            postProcessing.put("chunk_diffusion_count", chunkDiffusionCount);
            body.put("post_processing", postProcessing);

            String bodyStr = JSONUtil.toJson(body);
            RawResponse response = json("SearchKnowledge", null, bodyStr);

            if (response.getCode() != SdkError.SUCCESS.getNumber()) {
                log.error(
                        "SearchKnowledge request:{}, raw response:{}",
                        bodyStr,
                        response.getException());
                return Collections.emptyList();
            }

            log.debug(
                    "SearchKnowledge request:{}, raw response:{}",
                    bodyStr,
                    JSONUtil.parseJson(response.getData()));

            JsonNode rootNode = JSONUtil.parseJson(response.getData());
            JsonNode resultList = rootNode.path("data").path("result_list");

            List<KnowledgebaseEntry> entries = new ArrayList<>();
            if (!resultList.isMissingNode() && !resultList.isNull() && resultList.isArray()) {
                for (JsonNode result : resultList) {
                    String content = result.path("content").asText("");
                    JsonNode docMetaRawStr = result.path("doc_info").path("doc_meta");
                    Map<String, String> entryMetadata = new HashMap<>();
                    if (!docMetaRawStr.isMissingNode()
                            && !docMetaRawStr.isNull()
                            && docMetaRawStr.isTextual()) {
                        JsonNode docMetaList = JSONUtil.parseJson(docMetaRawStr.asText());
                        if (docMetaList.isArray()) {
                            for (JsonNode meta : docMetaList) {
                                String fieldName = meta.path("field_name").asText();
                                String fieldValue = meta.path("field_value").asText();
                                entryMetadata.put(fieldName, fieldValue);
                            }
                        }
                    }
                    entries.add(new KnowledgebaseEntry(content, entryMetadata));
                }
            }
            return entries;
        } catch (IOException e) {
            log.error("searchKnowledge failed", e);
            return Collections.emptyList();
        }
    }
}
