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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.ClearEnvironmentVariable;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

class ArkApiKeyAuthClientTest {

    @Test
    @SetEnvironmentVariable(key = "MODEL_AGENT_API_KEY_ID", value = "123")
    @SetEnvironmentVariable(key = "MODEL_AGENT_PROJECT_NAME", value = "agent-project")
    void resolveWithApiKeyIdFetchesRawApiKeyDirectly() {
        RecordingOpenApi openApi = new RecordingOpenApi(rawApiKeyResponse("resolved-api-key"));

        String apiKey = new ArkApiKeyAuthClient(openApi).resolve();

        assertThat(apiKey).isEqualTo("resolved-api-key");
        assertThat(openApi.calls()).hasSize(1);
        assertThat(openApi.calls().get(0).action()).isEqualTo("GetRawApiKey");
        assertThat(openApi.calls().get(0).body().get("Id")).isEqualTo(123L);
        assertThat(openApi.calls().get(0).body().get("ProjectName")).isEqualTo("agent-project");
    }

    @Test
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_KEY_ID")
    @SetEnvironmentVariable(key = "MODEL_AGENT_API_KEY_NAME", value = "target-key")
    void resolveWithApiKeyNamePaginatesUntilNameMatches() {
        RecordingOpenApi openApi =
                new RecordingOpenApi(
                        listApiKeysResponse(101, apiKeyItem("1", "other-key")),
                        listApiKeysResponse(101, apiKeyItem("2", "target-key")),
                        rawApiKeyResponse("target-api-key"));

        String apiKey = new ArkApiKeyAuthClient(openApi).resolve();

        assertThat(apiKey).isEqualTo("target-api-key");
        assertThat(openApi.actions()).containsExactly("ListApiKeys", "ListApiKeys", "GetRawApiKey");
        assertThat(openApi.calls().get(0).body().get("PageNumber")).isEqualTo(1);
        assertThat(openApi.calls().get(1).body().get("PageNumber")).isEqualTo(2);
        assertThat(openApi.calls().get(2).body().get("Id")).isEqualTo(2L);
    }

    @Test
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_KEY_ID")
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_KEY_NAME")
    void resolveWithoutApiKeySelectionUsesFirstAvailableApiKey() {
        RecordingOpenApi openApi =
                new RecordingOpenApi(
                        listApiKeysResponse(
                                2,
                                apiKeyItem("first-id", "first-key"),
                                apiKeyItem("second-id", "second-key")),
                        rawApiKeyResponse("first-api-key"));

        String apiKey = new ArkApiKeyAuthClient(openApi).resolve();

        assertThat(apiKey).isEqualTo("first-api-key");
        assertThat(openApi.actions()).containsExactly("ListApiKeys", "GetRawApiKey");
        assertThat(openApi.calls().get(1).body().get("Id")).isEqualTo("first-id");
    }

    @Test
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_KEY_ID")
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_KEY_NAME")
    void resolveSupportsSdkUnwrappedResultResponses() {
        RecordingOpenApi openApi =
                new RecordingOpenApi(
                        unwrappedListApiKeysResult(1, apiKeyItem("sdk-first-id", "sdk-first-key")),
                        unwrappedRawApiKeyResult("sdk-first-api-key"));

        String apiKey = new ArkApiKeyAuthClient(openApi).resolve();

        assertThat(apiKey).isEqualTo("sdk-first-api-key");
        assertThat(openApi.actions()).containsExactly("ListApiKeys", "GetRawApiKey");
        assertThat(openApi.calls().get(1).body().get("Id")).isEqualTo("sdk-first-id");
    }

    @Test
    @SetEnvironmentVariable(key = "MODEL_AGENT_API_KEY_ID", value = "api-key-id")
    void resolveRetriesTransientRawApiKeyErrors() {
        RecordingOpenApi openApi =
                new RecordingOpenApi(
                        errorResponse("Throttling", "please retry"),
                        rawApiKeyResponse("resolved-api-key"));

        String apiKey = new ArkApiKeyAuthClient(openApi).resolve();

        assertThat(apiKey).isEqualTo("resolved-api-key");
        assertThat(openApi.actions()).containsExactly("GetRawApiKey", "GetRawApiKey");
    }

    private static Map<String, Object> listApiKeysResponse(
            int totalCount, Map<String, Object>... items) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("TotalCount", totalCount);
        result.put("Items", List.of(items));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("Result", result);
        return response;
    }

    private static Map<String, Object> unwrappedListApiKeysResult(
            int totalCount, Map<String, Object>... items) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("TotalCount", totalCount);
        result.put("Items", List.of(items));
        return result;
    }

    private static Map<String, Object> rawApiKeyResponse(String apiKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ApiKey", apiKey);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("Result", result);
        return response;
    }

    private static Map<String, Object> unwrappedRawApiKeyResult(String apiKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ApiKey", apiKey);
        return result;
    }

    private static Map<String, Object> errorResponse(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("Code", code);
        error.put("Message", message);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("Error", error);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ResponseMetadata", metadata);
        return response;
    }

    private static Map<String, Object> apiKeyItem(String id, String name) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("Id", id);
        item.put("Name", name);
        return item;
    }

    private static final class RecordingOpenApi implements ArkApiKeyAuthClient.ArkOpenApi {

        private final Queue<Map<String, Object>> responses = new ArrayDeque<>();
        private final List<CallRecord> calls = new ArrayList<>();

        @SafeVarargs
        private RecordingOpenApi(Map<String, Object>... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public Map<String, Object> call(String action, Map<String, Object> body) {
            calls.add(new CallRecord(action, new LinkedHashMap<>(body)));
            return responses.remove();
        }

        private List<CallRecord> calls() {
            return calls;
        }

        private List<String> actions() {
            return calls.stream().map(CallRecord::action).toList();
        }
    }

    private record CallRecord(String action, Map<String, Object> body) {}
}
