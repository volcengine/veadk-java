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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.volcengine.error.SdkError;
import com.volcengine.model.response.RawResponse;
import com.volcengine.veadk.utils.JSONUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ArkApiKeyResolverTest {

    @Test
    void resolveWithoutNameUsesFirstApiKey() throws Exception {
        ArkApiKeyResolver resolver = Mockito.spy(newResolver());
        doReturn(
                        success(
                                "{\"Result\":{\"TotalCount\":1,\"Items\":[{\"Id\":\"101\",\"Name\":\"first\"}]}}"),
                        success("{\"Result\":{\"ApiKey\":\"sk-FIRST\"}}"))
                .when(resolver)
                .json(anyString(), anyList(), anyString());

        assertThat(resolver.resolve(null)).isEqualTo("sk-FIRST");

        ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(resolver, Mockito.times(2))
                .json(actionCaptor.capture(), anyList(), bodyCaptor.capture());
        assertThat(actionCaptor.getAllValues()).containsExactly("ListApiKeys", "GetRawApiKey");

        JsonNode getRawBody = JSONUtil.parseJson(bodyCaptor.getAllValues().get(1));
        assertThat(getRawBody.path("ProjectName").asText()).isEqualTo("default");
        assertThat(getRawBody.path("Id").isNumber()).isTrue();
        assertThat(getRawBody.path("Id").asLong()).isEqualTo(101L);
    }

    @Test
    void resolveByNameScansPagesUntilMatched() throws Exception {
        ArkApiKeyResolver resolver = Mockito.spy(newResolver());
        doReturn(
                        success(
                                "{\"Result\":{\"TotalCount\":3,\"Items\":[{\"Id\":\"1\",\"Name\":\"old\"}]}}"),
                        success(
                                "{\"Result\":{\"TotalCount\":3,\"Items\":[{\"Id\":\"2\",\"Name\":\"older\"}]}}"),
                        success(
                                "{\"Result\":{\"TotalCount\":3,\"Items\":[{\"Id\":\"wanted-id\",\"Name\":\"wanted\"}]}}"),
                        success("{\"Result\":{\"ApiKey\":\"sk-WANTED\"}}"))
                .when(resolver)
                .json(anyString(), anyList(), anyString());

        assertThat(resolver.resolve("wanted")).isEqualTo("sk-WANTED");

        ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(resolver, Mockito.times(4))
                .json(actionCaptor.capture(), anyList(), bodyCaptor.capture());
        assertThat(actionCaptor.getAllValues())
                .containsExactly("ListApiKeys", "ListApiKeys", "ListApiKeys", "GetRawApiKey");

        JsonNode secondListBody = JSONUtil.parseJson(bodyCaptor.getAllValues().get(1));
        JsonNode thirdListBody = JSONUtil.parseJson(bodyCaptor.getAllValues().get(2));
        JsonNode getRawBody = JSONUtil.parseJson(bodyCaptor.getAllValues().get(3));
        assertThat(secondListBody.path("PageNumber").asInt()).isEqualTo(2);
        assertThat(thirdListBody.path("PageNumber").asInt()).isEqualTo(3);
        assertThat(getRawBody.path("Id").asText()).isEqualTo("wanted-id");
    }

    @Test
    void resolveByNameFailsWhenNotFound() throws Exception {
        ArkApiKeyResolver resolver = Mockito.spy(newResolver());
        doReturn(
                        success(
                                "{\"Result\":{\"TotalCount\":2,\"Items\":[{\"Id\":\"1\",\"Name\":\"old\"}]}}"),
                        success(
                                "{\"Result\":{\"TotalCount\":2,\"Items\":[{\"Id\":\"2\",\"Name\":\"older\"}]}}"))
                .when(resolver)
                .json(anyString(), anyList(), anyString());

        assertThatThrownBy(() -> resolver.resolve("wanted"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARK API Key named 'wanted' not found")
                .hasMessageContaining("scanned 2 keys");
    }

    @Test
    void openApiErrorFailsClearly() throws Exception {
        ArkApiKeyResolver resolver = Mockito.spy(newResolver());
        doReturn(new RawResponse(null, SdkError.EHTTP.getNumber(), new Exception("denied")))
                .when(resolver)
                .json(anyString(), anyList(), anyString());

        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARK OpenAPI ListApiKeys failed")
                .hasMessageContaining("denied");
    }

    @Test
    void bytePlusUsesBytePlusControlPlane() {
        ArkApiKeyResolver resolver =
                new ArkApiKeyResolver("ak", "sk", "token", "cn-beijing", "byteplus");

        assertThat(resolver.getServiceInfo().getHost()).isEqualTo("open.byteplusapi.com");
        assertThat(resolver.getRegion()).isEqualTo("ap-southeast-1");
        assertThat(resolver.getSessionToken()).isEqualTo("token");
    }

    private static ArkApiKeyResolver newResolver() {
        return new ArkApiKeyResolver("ak", "sk", "", "cn-beijing", "");
    }

    private static RawResponse success(String json) {
        return new RawResponse(json.getBytes(), SdkError.SUCCESS.getNumber(), null);
    }
}
