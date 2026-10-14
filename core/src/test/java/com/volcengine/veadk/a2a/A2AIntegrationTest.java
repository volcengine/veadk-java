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
package com.volcengine.veadk.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.Runner;
import com.volcengine.veadk.memory.ShortTermMemory;
import io.a2a.spec.TransportProtocol;
import io.reactivex.rxjava3.core.Flowable;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class A2AIntegrationTest {

    @Test
    void serverAndRemoteAgentExchangeMessageOverRest() throws Exception {
        int port = availablePort();
        String url = "http://127.0.0.1:" + port;
        Agent served =
                Agent.builder()
                        .name("weather_agent")
                        .description("Weather specialist")
                        .model(new StaticLlm("sunny in Beijing"))
                        .build();

        RemoteVeAgent remote = null;
        try (VeA2AServer server =
                new VeA2AServer(served, url, "weather_app", ShortTermMemory.local())
                        .start("127.0.0.1", port)) {
            remote = RemoteVeAgent.builder().name("weather_agent").url(url).build();
            assertThat(remote.description()).isEqualTo("Weather specialist");
            assertThat(remote.agentCard().preferredTransport())
                    .isEqualTo(TransportProtocol.HTTP_JSON.asString());
            assertThat(new Runner(remote).run("What is the weather?"))
                    .isEqualTo("sunny in Beijing");
        } finally {
            if (remote != null) {
                remote.close().blockingAwait();
            }
            served.close().blockingAwait();
        }
    }

    @Test
    void generatedCardMatchesPythonDefaults() {
        Agent agent =
                Agent.builder()
                        .name("card_agent")
                        .description("Card agent")
                        .model(new StaticLlm("ok"))
                        .build();
        try {
            var card = AgentCards.fromAgent(agent, "http://localhost:9000");

            assertThat(card.name()).isEqualTo("card_agent");
            assertThat(card.defaultInputModes()).containsExactly("text");
            assertThat(card.defaultOutputModes()).containsExactly("text");
            assertThat(card.provider().organization()).isEqualTo("veadk");
            assertThat(card.skills())
                    .singleElement()
                    .satisfies(skill -> assertThat(skill.name()).isEqualTo("chat"));
        } finally {
            agent.close().blockingAwait();
        }
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class StaticLlm extends BaseLlm {
        private final String answer;

        private StaticLlm(String answer) {
            super("static-a2a-model");
            this.answer = answer;
        }

        @Override
        public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
            return Flowable.just(
                    LlmResponse.builder()
                            .content(
                                    Content.builder()
                                            .role("model")
                                            .parts(Part.fromText(answer))
                                            .build())
                            .build());
        }

        @Override
        public BaseLlmConnection connect(LlmRequest llmRequest) {
            throw new UnsupportedOperationException();
        }
    }
}
