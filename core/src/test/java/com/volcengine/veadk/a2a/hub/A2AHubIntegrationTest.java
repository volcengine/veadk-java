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
package com.volcengine.veadk.a2a.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.TransportProtocol;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class A2AHubIntegrationTest {

    @Test
    void createsGroupRegistersAndFiltersAgentCards() throws Exception {
        try (A2AHubServer server = new A2AHubServer().start("127.0.0.1", 0)) {
            A2AHubClient client = new A2AHubClient(URI.create("http://127.0.0.1:" + server.port()));
            client.createGroup("weather-team");
            client.registerAgent("weather-team", "weather", card("weather"));
            client.registerAgent("weather-team", "finance", card("finance"));

            assertThat(client.getAgentCards("weather-team"))
                    .extracting(A2AHubClient.AgentInformation::agentId)
                    .containsExactlyInAnyOrder("weather", "finance");
            assertThat(client.getAgentCards("weather-team", List.of("finance")))
                    .singleElement()
                    .satisfies(
                            info -> {
                                assertThat(info.agentId()).isEqualTo("finance");
                                assertThat(info.agentCard().path("name").asText())
                                        .isEqualTo("finance");
                            });
        }
    }

    @Test
    void rejectsUnknownGroupAndDuplicateAgent() throws Exception {
        try (A2AHubServer server = new A2AHubServer().start("127.0.0.1", 0)) {
            A2AHubClient client = new A2AHubClient(URI.create("http://127.0.0.1:" + server.port()));
            assertThatThrownBy(() -> client.registerAgent("missing", "weather", card("weather")))
                    .hasMessageContaining("group missing not exist");

            client.createGroup("team");
            client.registerAgent("team", "weather", card("weather"));
            assertThatThrownBy(() -> client.registerAgent("team", "weather", card("weather")))
                    .hasMessageContaining("already exist");
        }
    }

    private static AgentCard card(String name) {
        return new AgentCard.Builder()
                .name(name)
                .description(name + " agent")
                .url("http://example.test/" + name)
                .version("1.0")
                .capabilities(new AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of())
                .preferredTransport(TransportProtocol.HTTP_JSON.asString())
                .build();
    }
}
