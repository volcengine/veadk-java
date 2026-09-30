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
package com.volcengine.veadk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.google.adk.agents.LlmAgent;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.volcengine.veadk.knowledgebase.BaseKnowledgebaseService;
import com.volcengine.veadk.utils.JSONUtil;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentMetadataExtractorTest {

    @Test
    void extractsVeadkAgentMetadataFromSnapshot() {
        LlmAgent child =
                LlmAgent.builder()
                        .name("child_agent")
                        .description("child description")
                        .instruction("child instruction")
                        .model(new TestLlm("child-model"))
                        .build();
        Agent agent =
                Agent.builder()
                        .name("root_agent")
                        .description("root description")
                        .instruction("root instruction")
                        .model(new TestLlm("root-model"))
                        .tools(
                                new TestTool("explicit_lookup"),
                                new TestTool(AgentMetadataExtractor.WEB_SEARCH_TOOL_NAME))
                        .subAgents(child)
                        .knowledgebase(mock(BaseKnowledgebaseService.class))
                        .longTermMemory(mock(BaseMemoryService.class))
                        .autoSaveSession(true)
                        .build();

        AgentMetadata metadata = AgentMetadataExtractor.extract(agent);

        assertThat(metadata.veadkAgent()).isTrue();
        assertThat(metadata.id()).isEqualTo("root_agent");
        assertThat(metadata.name()).isEqualTo("root_agent");
        assertThat(metadata.description()).isEqualTo("root description");
        assertThat(metadata.instructionSummary()).isEqualTo("root instruction");
        assertThat(metadata.modelName()).isEqualTo("root-model");
        assertThat(metadata.autoSaveSession()).isTrue();
        assertThat(metadata.tools())
                .extracting(AgentMetadata.ToolMetadata::name)
                .containsExactly(
                        "explicit_lookup",
                        AgentMetadataExtractor.WEB_SEARCH_TOOL_NAME,
                        AgentMetadataExtractor.KNOWLEDGEBASE_TOOL_NAME,
                        AgentMetadataExtractor.MEMORY_TOOL_NAME);
        assertThat(metadata.tools())
                .extracting(AgentMetadata.ToolMetadata::source)
                .containsExactly(
                        AgentMetadataExtractor.TOOL_SOURCE_EXPLICIT,
                        AgentMetadataExtractor.TOOL_SOURCE_EXPLICIT,
                        AgentMetadataExtractor.TOOL_SOURCE_AUTO,
                        AgentMetadataExtractor.TOOL_SOURCE_AUTO);
        assertThat(metadata.subAgents()).hasSize(1);
        assertThat(metadata.subAgents().get(0).id()).isEqualTo("root_agent/child_agent");
        assertThat(component(metadata, "knowledgebase").enabled()).isTrue();
        assertThat(component(metadata, "longTermMemory").enabled()).isTrue();
        assertThat(searchSource(metadata, "web").enabled()).isTrue();
        assertThat(searchSource(metadata, "knowledge").enabled()).isTrue();
        assertThat(searchSource(metadata, "memory").enabled()).isTrue();
    }

    @Test
    void fallsBackToLlmAgentPublicAccessors() {
        LlmAgent agent =
                LlmAgent.builder()
                        .name("adk_agent")
                        .description("plain ADK agent")
                        .instruction("plain instruction")
                        .model(new TestLlm("adk-model"))
                        .tools(new TestTool(AgentMetadataExtractor.MEMORY_TOOL_NAME))
                        .build();

        AgentMetadata metadata = AgentMetadataExtractor.extract(agent);

        assertThat(metadata.veadkAgent()).isFalse();
        assertThat(metadata.name()).isEqualTo("adk_agent");
        assertThat(metadata.instructionSummary()).isEqualTo("plain instruction");
        assertThat(metadata.modelName()).isEqualTo("adk-model");
        assertThat(metadata.autoSaveSession()).isFalse();
        assertThat(metadata.tools()).hasSize(1);
        assertThat(metadata.tools().get(0).source())
                .isEqualTo(AgentMetadataExtractor.TOOL_SOURCE_ADK);
        assertThat(component(metadata, "knowledgebase").enabled()).isFalse();
        assertThat(component(metadata, "longTermMemory").enabled()).isTrue();
        assertThat(searchSource(metadata, "memory").toolName())
                .isEqualTo(AgentMetadataExtractor.MEMORY_TOOL_NAME);
    }

    @Test
    void metadataCanBeSerializedAsJson() {
        Agent agent = Agent.builder().name("json_agent").model(new TestLlm("json-model")).build();

        String json = JSONUtil.toJson(AgentMetadataExtractor.extract(agent));

        assertThat(json).contains("\"name\":\"json_agent\"");
        assertThat(json).contains("\"tools\"");
        assertThat(json).contains("\"components\"");
        assertThat(json).contains("\"searchSources\"");
    }

    private static AgentMetadata.ComponentMetadata component(
            AgentMetadata metadata, String componentName) {
        return metadata.components().stream()
                .filter(component -> componentName.equals(component.name()))
                .findFirst()
                .orElseThrow();
    }

    private static AgentMetadata.SearchSourceMetadata searchSource(
            AgentMetadata metadata, String sourceName) {
        return metadata.searchSources().stream()
                .filter(source -> sourceName.equals(source.name()))
                .findFirst()
                .orElseThrow();
    }

    private static final class TestLlm extends BaseLlm {
        private TestLlm(String model) {
            super(model);
        }

        @Override
        public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
            return Flowable.empty();
        }

        @Override
        public BaseLlmConnection connect(LlmRequest llmRequest) {
            throw new UnsupportedOperationException("connect is not used in this test.");
        }
    }

    private static final class TestTool extends BaseTool {
        private TestTool(String name) {
            super(name, "test tool");
        }

        @Override
        public Single<Map<String, Object>> runAsync(
                Map<String, Object> args, ToolContext toolContext) {
            return Single.just(Map.of());
        }
    }
}
