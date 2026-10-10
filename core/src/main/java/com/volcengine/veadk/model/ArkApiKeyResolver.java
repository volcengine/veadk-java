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
package com.volcengine.veadk.model;

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
import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;

/** Resolves raw Ark API keys through Volcengine OpenAPI. */
public class ArkApiKeyResolver extends BaseServiceImpl {

    private static final String PROJECT_NAME = "default";
    private static final int PAGE_SIZE = 100;
    private static final String SERVICE = "ark";
    private static final String VERSION = "2024-01-01";
    private static final String ACTION_LIST_API_KEYS = "ListApiKeys";
    private static final String ACTION_GET_RAW_API_KEY = "GetRawApiKey";
    private static final String VOLCENGINE_OPENAPI_HOST = "open.volcengineapi.com";
    private static final String BYTEPLUS_OPENAPI_HOST = "open.byteplusapi.com";
    private static final String DEFAULT_REGION = "cn-beijing";
    private static final String BYTEPLUS_REGION = "ap-southeast-1";

    private static final Map<String, ApiInfo> API_INFO_LIST = createApiInfoList();

    public ArkApiKeyResolver(
            String accessKey,
            String secretKey,
            String sessionToken,
            String region,
            String cloudProvider) {
        super(createServiceInfo(region, cloudProvider), API_INFO_LIST);
        setAccessKey(accessKey);
        setSecretKey(secretKey);
        if (StringUtils.isNotBlank(sessionToken)) {
            setSessionToken(sessionToken);
        }
    }

    public String resolve(String apiKeyName) {
        String apiKeyId =
                StringUtils.isBlank(apiKeyName)
                        ? firstApiKeyId()
                        : apiKeyIdByName(apiKeyName.trim());
        return rawApiKey(apiKeyId);
    }

    private String firstApiKeyId() {
        JsonNode result = listApiKeys(1);
        JsonNode items = result.path("Items");
        if (!items.isArray() || items.isEmpty()) {
            throw new IllegalStateException(
                    "No ARK API keys found in project '" + PROJECT_NAME + "'.");
        }
        String apiKeyId = items.get(0).path("Id").asText();
        if (StringUtils.isBlank(apiKeyId)) {
            throw new IllegalStateException("The first ARK API key does not contain an Id.");
        }
        return apiKeyId;
    }

    private String apiKeyIdByName(String apiKeyName) {
        int page = 1;
        int scanned = 0;
        int total = 0;
        while (true) {
            JsonNode result = listApiKeys(page);
            total = result.path("TotalCount").asInt(total);
            JsonNode items = result.path("Items");
            if (!items.isArray() || items.isEmpty()) {
                break;
            }
            for (JsonNode item : items) {
                if (apiKeyName.equals(item.path("Name").asText())) {
                    String apiKeyId = item.path("Id").asText();
                    if (StringUtils.isBlank(apiKeyId)) {
                        throw new IllegalStateException(
                                "ARK API Key named '" + apiKeyName + "' does not contain an Id.");
                    }
                    return apiKeyId;
                }
            }
            scanned += items.size();
            if (scanned >= total) {
                break;
            }
            page += 1;
        }
        throw new IllegalStateException(
                "ARK API Key named '"
                        + apiKeyName
                        + "' not found in project '"
                        + PROJECT_NAME
                        + "' (scanned "
                        + scanned
                        + " keys).");
    }

    private JsonNode listApiKeys(int pageNumber) {
        Map<String, Object> body = new HashMap<>();
        body.put("ProjectName", PROJECT_NAME);
        body.put("Filter", Map.of("AllowAll", true));
        body.put("PageNumber", pageNumber);
        body.put("PageSize", PAGE_SIZE);

        return callArkOpenApi(ACTION_LIST_API_KEYS, body).path("Result");
    }

    private String rawApiKey(String apiKeyId) {
        Map<String, Object> body = new HashMap<>();
        body.put("ProjectName", PROJECT_NAME);
        body.put("Id", normalizeApiKeyId(apiKeyId));

        String apiKey =
                callArkOpenApi(ACTION_GET_RAW_API_KEY, body).path("Result").path("ApiKey").asText();
        if (StringUtils.isBlank(apiKey)) {
            throw new IllegalStateException("Failed to get ARK API key from OpenAPI response.");
        }
        return apiKey;
    }

    private JsonNode callArkOpenApi(String action, Map<String, Object> body) {
        try {
            RawResponse response = json(action, actionParams(action), JSONUtil.toJson(body));
            if (response.getCode() != SdkError.SUCCESS.getNumber()) {
                Exception exception = response.getException();
                String message =
                        exception == null
                                ? response.getVerboseExceptionMessage()
                                : exception.getMessage();
                throw new IllegalStateException(
                        "ARK OpenAPI " + action + " failed: " + message, exception);
            }
            return JSONUtil.parseJson(response.getData());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ARK OpenAPI " + action + " failed.", e);
        }
    }

    private static Object normalizeApiKeyId(String apiKeyId) {
        if (apiKeyId.matches("\\d+")) {
            return Long.parseLong(apiKeyId);
        }
        return apiKeyId;
    }

    private static List<NameValuePair> actionParams(String action) {
        return Arrays.asList(
                new BasicNameValuePair("Action", action),
                new BasicNameValuePair("Version", VERSION));
    }

    private static Map<String, ApiInfo> createApiInfoList() {
        Map<String, ApiInfo> apiInfo = new HashMap<>();
        apiInfo.put(ACTION_LIST_API_KEYS, createPostJsonApiInfo());
        apiInfo.put(ACTION_GET_RAW_API_KEY, createPostJsonApiInfo());
        return apiInfo;
    }

    private static ApiInfo createPostJsonApiInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put(Const.Method, "POST");
        info.put(Const.Path, "/");
        info.put(
                Const.Header,
                Arrays.asList(
                        new BasicHeader("Accept", "application/json"),
                        new BasicHeader("Content-Type", "application/json")));
        return new ApiInfo(info);
    }

    private static ServiceInfo createServiceInfo(String region, String cloudProvider) {
        Map<String, Object> info = new HashMap<>();
        info.put(Const.CONNECTION_TIMEOUT, 5000);
        info.put(Const.SOCKET_TIMEOUT, 30000);
        info.put(Const.Scheme, "https");
        info.put(Const.Host, openApiHost(cloudProvider));
        info.put(
                Const.Header,
                new ArrayList<Header>() {
                    {
                        add(new BasicHeader("Accept", "application/json"));
                    }
                });
        info.put(Const.Credentials, new Credentials(openApiRegion(region, cloudProvider), SERVICE));
        return new ServiceInfo(info);
    }

    private static String openApiHost(String cloudProvider) {
        return isBytePlus(cloudProvider) ? BYTEPLUS_OPENAPI_HOST : VOLCENGINE_OPENAPI_HOST;
    }

    private static String openApiRegion(String region, String cloudProvider) {
        if (isBytePlus(cloudProvider)) {
            return BYTEPLUS_REGION;
        }
        return StringUtils.defaultIfBlank(region, DEFAULT_REGION);
    }

    private static boolean isBytePlus(String cloudProvider) {
        return "byteplus".equals(StringUtils.trimToEmpty(cloudProvider).toLowerCase(Locale.ROOT));
    }
}
