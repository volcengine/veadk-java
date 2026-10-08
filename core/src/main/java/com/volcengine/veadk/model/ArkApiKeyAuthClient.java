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

import com.google.gson.reflect.TypeToken;
import com.squareup.okhttp.Call;
import com.volcengine.ApiClient;
import com.volcengine.ApiException;
import com.volcengine.ApiResponse;
import com.volcengine.Pair;
import com.volcengine.sign.Credentials;
import com.volcengine.veadk.utils.EnvUtil;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/** Resolves an Ark runtime API key from Volcano Engine AK/SK credentials. */
final class ArkApiKeyAuthClient {

    private static final String API_VERSION = "2024-01-01";
    private static final String AUTH_NAME = "volcengineSign";
    private static final String BYTEPLUS_CLOUD_PROVIDER = "byteplus";
    private static final String BYTEPLUS_REGION = "ap-southeast-1";
    private static final String BYTEPLUS_ENDPOINT = "open.byteplusapi.com";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_RAW_KEY_ATTEMPTS = 3;
    private static final int RAW_API_KEY_RETRYABLE_ERROR_CODE_NUMBER = 100016;

    private final ArkOpenApi openApi;

    ArkApiKeyAuthClient() {
        this(new VolcengineArkOpenApi(createApiClient()));
    }

    ArkApiKeyAuthClient(ArkOpenApi openApi) {
        this.openApi = openApi;
    }

    String resolve() {
        String apiKeyId = StringUtils.trimToNull(EnvUtil.getModelAgentApiKeyId());
        if (apiKeyId != null) {
            return getRawApiKey(apiKeyId);
        }

        String apiKeyName = StringUtils.trimToNull(EnvUtil.getModelAgentApiKeyName());
        if (apiKeyName != null) {
            return getRawApiKey(findApiKeyIdByName(apiKeyName));
        }

        return getRawApiKey(findFirstApiKeyId());
    }

    private String findApiKeyIdByName(String apiKeyName) {
        int pageNumber = 1;
        while (true) {
            ListApiKeysResult result = listApiKeys(pageNumber);
            for (Map<String, Object> item : result.items()) {
                if (apiKeyName.equals(stringValue(item.get("Name")))) {
                    String apiKeyId = StringUtils.trimToNull(stringValue(item.get("Id")));
                    if (apiKeyId != null) {
                        return apiKeyId;
                    }
                }
            }
            if (result.items().isEmpty() || pageNumber * PAGE_SIZE >= result.totalCount()) {
                break;
            }
            pageNumber++;
        }
        throw new IllegalStateException("Ark API key named '" + apiKeyName + "' was not found.");
    }

    private String findFirstApiKeyId() {
        ListApiKeysResult result = listApiKeys(1);
        for (Map<String, Object> item : result.items()) {
            String apiKeyId = StringUtils.trimToNull(stringValue(item.get("Id")));
            if (apiKeyId != null) {
                return apiKeyId;
            }
        }
        throw new IllegalStateException(
                "No Ark API key was found in project '"
                        + EnvUtil.getModelAgentProjectName()
                        + "'.");
    }

    private ListApiKeysResult listApiKeys(int pageNumber) {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("AllowAll", true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ProjectName", EnvUtil.getModelAgentProjectName());
        body.put("Filter", filter);
        body.put("PageNumber", pageNumber);
        body.put("PageSize", PAGE_SIZE);

        Map<String, Object> response = openApi.call("ListApiKeys", body);
        Map<String, Object> error = responseError(response);
        if (!error.isEmpty()) {
            throw new IllegalStateException(
                    "Ark ListApiKeys failed: " + stringValue(error.get("Message")));
        }
        Map<String, Object> result = resultValue(response);
        return new ListApiKeysResult(
                listValue(result.get("Items")), intValue(result.get("TotalCount")));
    }

    private String getRawApiKey(String apiKeyId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Id", normalizeId(apiKeyId));
        body.put("ProjectName", EnvUtil.getModelAgentProjectName());

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_RAW_KEY_ATTEMPTS; attempt++) {
            Map<String, Object> response = openApi.call("GetRawApiKey", body);
            Map<String, Object> error = responseError(response);
            if (error.isEmpty()) {
                String apiKey =
                        StringUtils.trimToNull(stringValue(resultValue(response).get("ApiKey")));
                if (apiKey != null) {
                    return apiKey;
                }
                lastFailure =
                        new IllegalStateException("Ark GetRawApiKey returned an empty API key.");
            } else {
                lastFailure =
                        new IllegalStateException(
                                "Ark GetRawApiKey failed: " + stringValue(error.get("Message")));
            }
            if (attempt == MAX_RAW_KEY_ATTEMPTS || !isRetryable(error)) {
                break;
            }
        }
        throw lastFailure;
    }

    private static Object normalizeId(String apiKeyId) {
        String trimmed = apiKeyId.trim();
        if (!trimmed.matches("\\d+")) {
            return trimmed;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            return trimmed;
        }
    }

    private static Map<String, Object> responseError(Map<String, Object> response) {
        return mapValue(mapValue(response.get("ResponseMetadata")).get("Error"));
    }

    private static Map<String, Object> resultValue(Map<String, Object> response) {
        Map<String, Object> wrappedResult = mapValue(response.get("Result"));
        return wrappedResult.isEmpty() ? response : wrappedResult;
    }

    private static boolean isRetryable(Map<String, Object> error) {
        if (error.isEmpty()) {
            return false;
        }
        String code = stringValue(error.get("Code"));
        if (StringUtils.isBlank(code)) {
            code = stringValue(error.get("CodeN"));
        }
        return "InternalError".equals(code)
                || "InternalServiceTimeout".equals(code)
                || "RequestTimeout".equals(code)
                || "ServiceUnavailable".equals(code)
                || "Throttling".equals(code)
                || "TooManyRequests".equals(code)
                || String.valueOf(RAW_API_KEY_RETRYABLE_ERROR_CODE_NUMBER).equals(code);
    }

    private static String stringValue(Object value) {
        if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            long longValue = number.longValue();
            if (Double.isFinite(doubleValue) && doubleValue == longValue) {
                return String.valueOf(longValue);
            }
        }
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listValue(Object value) {
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return Collections.emptyList();
    }

    private static int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static ApiClient createApiClient() {
        String sessionToken = EnvUtil.getSessionToken();
        Credentials credentials =
                StringUtils.isBlank(sessionToken)
                        ? Credentials.getCredentials(EnvUtil.getAccessKey(), EnvUtil.getSecretKey())
                        : Credentials.getCredentials(
                                EnvUtil.getAccessKey(), EnvUtil.getSecretKey(), sessionToken);
        ApiClient apiClient =
                new ApiClient().setCredentials(credentials).setRegion(resolveRegion());
        if (BYTEPLUS_CLOUD_PROVIDER.equalsIgnoreCase(EnvUtil.getCloudProvider())) {
            apiClient.setEndpoint(BYTEPLUS_ENDPOINT);
        }
        return apiClient;
    }

    private static String resolveRegion() {
        if (BYTEPLUS_CLOUD_PROVIDER.equalsIgnoreCase(EnvUtil.getCloudProvider())) {
            return BYTEPLUS_REGION;
        }
        return EnvUtil.getRegion();
    }

    interface ArkOpenApi {
        Map<String, Object> call(String action, Map<String, Object> body);
    }

    private static final class VolcengineArkOpenApi implements ArkOpenApi {

        private static final Type RESPONSE_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

        private final ApiClient apiClient;

        private VolcengineArkOpenApi(ApiClient apiClient) {
            this.apiClient = apiClient;
        }

        @Override
        public Map<String, Object> call(String action, Map<String, Object> body) {
            try {
                Map<String, String> headers = new HashMap<>();
                String accept = apiClient.selectHeaderAccept(new String[] {"application/json"});
                if (accept != null) {
                    headers.put("Accept", accept);
                }
                headers.put(
                        "Content-Type",
                        apiClient.selectHeaderContentType(new String[] {"application/json"}));

                String path = "/" + action + "/" + API_VERSION + "/ark/post/application_json/";
                Call call =
                        apiClient.buildCall(
                                path,
                                "POST",
                                new ArrayList<Pair>(),
                                new ArrayList<Pair>(),
                                body,
                                headers,
                                new HashMap<String, Object>(),
                                new String[] {AUTH_NAME},
                                null);
                ApiResponse<Map<String, Object>> response = apiClient.execute(call, RESPONSE_TYPE);
                return response.getData() == null ? Collections.emptyMap() : response.getData();
            } catch (ApiException e) {
                throw new IllegalStateException(
                        "Failed to call Ark OpenAPI action " + action + ": " + e.getResponseBody(),
                        e);
            }
        }
    }

    private record ListApiKeysResult(List<Map<String, Object>> items, int totalCount) {}
}
