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
package com.volcengine.veadk.a2a.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.volcengine.veadk.integration.agentkit.AgentKitWrapper;
import com.volcengine.veadk.utils.JSONUtil;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentKitA2ARegistryClientTest {

    @Test
    @SuppressWarnings("unchecked")
    void truncatesPromptAndReturnsOnlySanitizedCardFields() throws Exception {
        AgentKitWrapper wrapper = mock(AgentKitWrapper.class);
        String card =
                "{\"name\":\"weather\",\"description\":\"Weather agent\","
                        + "\"version\":\"1\",\"url\":\"https://secret.test/a2a\","
                        + "\"security\":[{\"bearer\":[\"secret-token\"]}],"
                        + "\"skills\":[{\"id\":\"weather\",\"name\":\"Weather\","
                        + "\"description\":\"Query weather\",\"tags\":[\"weather\"]}]}";
        when(wrapper.searchAgentCards(anyString(), anyString(), anyInt()))
                .thenReturn(
                        JSONUtil.parseJson(
                                "{\"AgentCards\":["
                                        + JSONUtil.toJson(card)
                                        + "],\"TotalCount\":1}"));
        AgentKitA2ARegistryClient client =
                new AgentKitA2ARegistryClient(
                        new AgentKitA2ARegistryConfig("space", "agentkit.test", "cn-beijing", 3),
                        wrapper);

        Map<String, Object> result = client.searchAgentCards("番茄炒蛋".repeat(300));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(wrapper).searchAgentCards(anyString(), prompt.capture(), anyInt());
        assertThat(prompt.getValue().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(2048);
        Map<String, Object> agent = ((List<Map<String, Object>>) result.get("agents")).get(0);
        assertThat(agent).containsEntry("name", "weather").doesNotContainKeys("url", "security");
        assertThat(JSONUtil.toJson(result))
                .doesNotContain("secret-token", "secret.test", "Authorization");
    }

    @Test
    void truncateUtf8NeverSplitsCodePoint() {
        String value = AgentKitA2ARegistryClient.truncateUtf8("天气很好", 5);

        assertThat(value).isEqualTo("天");
        assertThat(value.getBytes(StandardCharsets.UTF_8)).hasSize(3);
    }
}
