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
package com.volcengine.veadk.integration.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.volcengine.error.SdkError;
import com.volcengine.helper.Const;
import com.volcengine.model.ApiInfo;
import com.volcengine.model.Credentials;
import com.volcengine.model.ServiceInfo;
import com.volcengine.model.response.RawResponse;
import com.volcengine.service.BaseServiceImpl;
import com.volcengine.veadk.utils.JSONUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.http.Header;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSearchWrapper extends BaseServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(WebSearchWrapper.class);

    private static final String END_POINT = "mercury.volcengineapi.com";

    private static final ServiceInfo SERVICE_INFO =
            new ServiceInfo(
                    new HashMap<String, Object>() {
                        {
                            put(Const.CONNECTION_TIMEOUT, 5000);
                            put(Const.SOCKET_TIMEOUT, 5000);
                            put(Const.Host, END_POINT);
                            put(
                                    Const.Header,
                                    new ArrayList<Header>() {
                                        {
                                            add(new BasicHeader("Accept", "application/json"));
                                        }
                                    });
                            put(
                                    Const.Credentials,
                                    new Credentials("cn-beijing", "volc_torchlight_api"));
                        }
                    });

    private static final Map<String, ApiInfo> API_INFO_LIST =
            new HashMap<String, ApiInfo>() {
                {
                    put(
                            "WebSearch",
                            new ApiInfo(
                                    new HashMap<String, Object>() {
                                        {
                                            put(Const.Method, "POST");
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

    private static final List<NameValuePair> WEBSEARCH_PARAMS =
            Arrays.asList(
                    new BasicNameValuePair("Action", "WebSearch"),
                    new BasicNameValuePair("Version", "2025-01-01"));

    public WebSearchWrapper(String accessKey, String secretKey) {
        super(SERVICE_INFO, API_INFO_LIST);
        setAccessKey(accessKey);
        setSecretKey(secretKey);
    }

    public List<String> doWebSearch(String query) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("Query", query);
        body.put("Count", 5);
        body.put("SearchType", "web");
        body.put("NeedSummary", true);

        String bodyStr = JSONUtil.toJson(body);
        RawResponse response = json("WebSearch", WEBSEARCH_PARAMS, bodyStr);
        if (response.getCode() != SdkError.SUCCESS.getNumber()) {
            log.error(
                    "WebSearch request:{}, raw response:{}",
                    bodyStr,
                    response.getException().getMessage());
            throw response.getException();
        }

        log.debug(
                "WebSearch request:{}, raw response:{}",
                bodyStr,
                JSONUtil.parseJson(response.getData()));

        return extractSummaries(JSONUtil.parseJson(response.getData()));
    }

    private List<String> extractSummaries(JsonNode rootNode) {
        JsonNode webResultsNode = rootNode.path("Result").path("WebResults");

        if (webResultsNode.isMissingNode() || !webResultsNode.isArray()) {
            return new ArrayList<>();
        }

        return StreamSupport.stream(webResultsNode.spliterator(), false)
                .map(node -> node.path("Summary").asText())
                .collect(Collectors.toList());
    }
}
