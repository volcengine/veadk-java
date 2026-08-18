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

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.volcengine.veadk.agent.AgentMetadata.SkillSummary;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentMetadataTest {

    @Test
    void from_extractsToolsSearchSourcesAndTopology() {
        BaseAgent child = new EmptyAgent("child");
        LlmAgent root =
                LlmAgent.builder()
                        .name("root")
                        .description("root agent")
                        .model(new EmptyLlm())
                        .tools(new NamedTool("web_search"), new NamedTool("calculator"))
                        .subAgents(child)
                        .build();

        AgentMetadata metadata = AgentMetadata.from(root);

        assertThat(metadata.name()).isEqualTo("root");
        assertThat(metadata.model()).isEqualTo("test-model");
        assertThat(metadata.tools())
                .extracting(AgentMetadata.ToolSummary::name)
                .containsExactly("web_search", "calculator");
        assertThat(metadata.searchSources()).containsExactly("web");
        assertThat(metadata.subAgents()).extracting(AgentMetadata::name).containsExactly("child");
    }

    @Test
    void from_extractsAndDeduplicatesProviderComponentsAndSkills() {
        AgentMetadata metadata = AgentMetadata.from(new ComponentAgent());

        assertThat(metadata.searchSources()).containsExactly("custom", "knowledge", "memory");
        assertThat(metadata.components()).hasSize(2);
        assertThat(metadata.skills()).containsExactly(new SkillSummary("review", "Review code"));
    }

    private static final class ComponentAgent extends BaseAgent implements AgentComponentProvider {

        private ComponentAgent() {
            super("component-agent", "", List.of(), List.of(), List.of());
        }

        @Override
        public List<AgentComponent> agentComponents() {
            return List.of(
                    new AgentComponent("knowledgebase", "docs", "knowledgebase", "memory", ""),
                    new AgentComponent("knowledgebase", "docs", "knowledgebase", "memory", ""),
                    new AgentComponent("long_term_memory", "user-memory"));
        }

        @Override
        public List<SkillSummary> agentSkills() {
            return List.of(
                    new SkillSummary("review", "Review code"),
                    new SkillSummary("review", "Duplicate"));
        }

        @Override
        public Set<String> additionalSearchSources() {
            return Set.of("custom");
        }

        @Override
        protected Flowable<com.google.adk.events.Event> runAsyncImpl(
                InvocationContext invocationContext) {
            return Flowable.empty();
        }

        @Override
        protected Flowable<com.google.adk.events.Event> runLiveImpl(
                InvocationContext invocationContext) {
            return Flowable.empty();
        }
    }

    private static final class NamedTool extends BaseTool {
        private NamedTool(String name) {
            super(name, name);
        }
    }

    private static final class EmptyLlm extends BaseLlm {
        private EmptyLlm() {
            super("test-model");
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

    private static final class EmptyAgent extends BaseAgent {
        private EmptyAgent(String name) {
            super(name, "", List.of(), List.of(), List.of());
        }

        @Override
        protected Flowable<com.google.adk.events.Event> runAsyncImpl(
                InvocationContext invocationContext) {
            return Flowable.empty();
        }

        @Override
        protected Flowable<com.google.adk.events.Event> runLiveImpl(
                InvocationContext invocationContext) {
            return Flowable.empty();
        }
    }
}
