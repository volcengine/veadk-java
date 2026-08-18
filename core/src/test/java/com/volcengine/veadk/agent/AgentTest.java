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
package com.volcengine.veadk.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.agents.Callbacks;
import com.google.adk.agents.LlmAgent;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.InMemorySessionService;
import com.volcengine.veadk.config.VeADKConfig;
import com.volcengine.veadk.processors.BaseRunProcessor;
import com.volcengine.veadk.runner.Runner;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentTest {

    @Test
    void builderCreatesLlmAgentAndPreservesVeadkComponents() {
        InMemorySessionService sessions = new InMemorySessionService();
        InMemoryMemoryService memory = new InMemoryMemoryService();
        BaseRunProcessor processor = (context, eventGenerator) -> eventGenerator.get();

        Agent agent =
                Agent.builder()
                        .id("agent-id")
                        .name("assistant")
                        .description("test assistant")
                        .instruction("help the user")
                        .model(new EmptyLlm("test-model"))
                        .shortTermMemory(sessions)
                        .longTermMemory(memory)
                        .runProcessor(processor)
                        .skills("review", "testing")
                        .enableAuthz(true)
                        .autoSaveSession(true)
                        .build();

        assertThat(agent).isInstanceOf(LlmAgent.class);
        assertThat(agent.id()).isEqualTo("agent-id");
        assertThat(agent.name()).isEqualTo("assistant");
        assertThat(agent.shortTermMemory()).isSameAs(sessions);
        assertThat(agent.longTermMemory()).isSameAs(memory);
        assertThat(agent.runProcessor()).isSameAs(processor);
        assertThat(agent.skills()).containsExactly("review", "testing");
        assertThat(agent.enableAuthz()).isTrue();
        assertThat(agent.autoSaveSession()).isTrue();
        assertThat(agent.metadata().model()).isEqualTo("test-model");
        assertThat(agent.metadata().searchSources()).containsExactly("memory");

        Runner runner = agent.newRunner();
        assertThat(runner.agent()).isSameAs(agent);
        assertThat(runner.sessionService()).isSameAs(sessions);
        assertThat(runner.memoryService()).isSameAs(memory);
        assertThat(runner.runProcessor()).isSameAs(processor);

        Runner directRunner = new Runner(agent);
        assertThat(directRunner.sessionService()).isSameAs(sessions);
        assertThat(directRunner.memoryService()).isSameAs(memory);
        assertThat(directRunner.runProcessor()).isSameAs(processor);
    }

    @Test
    void configuredBuilderCreatesArkModelWithoutReadingGlobalEnvironment() {
        VeADKConfig config =
                VeADKConfig.from(
                        Map.of(
                                "MODEL_AGENT_NAME", "configured-model",
                                "MODEL_AGENT_API_BASE", "https://ark.example/api/v3",
                                "MODEL_AGENT_API_KEY", "configured-key"));

        Agent agent = Agent.builder(config).name("configured-agent").build();

        assertThat(agent.name()).isEqualTo("configured-agent");
        assertThat(agent.metadata().model()).isEqualTo("configured-model");
    }

    @Test
    void autoSaveSessionAddsCallbackWithoutReplacingExistingCallbacks() {
        Callbacks.AfterAgentCallback existing = context -> Maybe.empty();
        SaveSessionPolicy policy = new SaveSessionPolicy(3, Duration.ofSeconds(5), true, false);

        Agent agent =
                Agent.builder()
                        .name("assistant")
                        .model(new EmptyLlm("test-model"))
                        .longTermMemory(new InMemoryMemoryService())
                        .afterAgentCallback(existing)
                        .autoSaveSession(true)
                        .saveSessionPolicy(policy)
                        .build();

        assertThat(agent.saveSessionPolicy()).isEqualTo(policy);
        assertThat(agent.afterAgentCallback())
                .hasValueSatisfying(
                        callbacks -> {
                            assertThat(callbacks).hasSize(2);
                            assertThat(callbacks.get(0)).isSameAs(existing);
                            assertThat(callbacks.get(1))
                                    .isInstanceOf(SaveSessionToMemoryCallback.class);
                        });
    }

    private static final class EmptyLlm extends BaseLlm {
        private EmptyLlm(String model) {
            super(model);
        }

        @Override
        public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
            return Flowable.empty();
        }

        @Override
        public BaseLlmConnection connect(LlmRequest llmRequest) {
            return null;
        }
    }
}
