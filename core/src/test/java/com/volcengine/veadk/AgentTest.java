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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.google.adk.agents.Callbacks;
import com.google.adk.agents.LlmAgent;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.LoadMemoryTool;
import com.google.adk.tools.ToolContext;
import com.volcengine.veadk.knowledgebase.BaseKnowledgebaseService;
import com.volcengine.veadk.knowledgebase.KnowledgebaseEntry;
import com.volcengine.veadk.knowledgebase.SearchKnowledgebaseResponse;
import com.volcengine.veadk.memory.SaveSessionToMemoryCallback;
import com.volcengine.veadk.memory.ShortTermMemory;
import com.volcengine.veadk.model.ArkLlm;
import com.volcengine.veadk.model.ArkLlmConfig;
import com.volcengine.veadk.model.ModelProvider;
import com.volcengine.veadk.model.OpenAiCompatibleLlm;
import com.volcengine.veadk.tools.knowledgebase.LoadKnowledgebaseTool;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.ClearEnvironmentVariable;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

class AgentTest {

    @Test
    @SetEnvironmentVariable(key = "MODEL_AGENT_API_KEY", value = "test-api-key")
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_BASE")
    void buildWithoutOverridesUsesVeadkDefaults() {
        Agent agent = Agent.builder().build();

        assertThat(agent.name()).isEqualTo(Agent.DEFAULT_NAME);
        assertThat(agent.description()).isEqualTo(Agent.DEFAULT_DESCRIPTION);
        assertThat(agent.veadkModelName()).isEqualTo(Agent.DEFAULT_MODEL_NAME);
        assertThat(agent.metadataSnapshot().instructionSummary())
                .isEqualTo(Agent.DEFAULT_INSTRUCTION);
        assertThat(agent.model()).isPresent();
        OpenAiCompatibleLlm llm =
                (OpenAiCompatibleLlm) agent.model().orElseThrow().model().orElseThrow();
        assertThat(llm.model()).isEqualTo(Agent.DEFAULT_MODEL_NAME);
        assertThat(llm.config().getApiKey()).isEqualTo("test-api-key");
        assertThat(llm.config().getBaseUrl()).isEqualTo(ArkLlmConfig.DEFAULT_API_BASE);
    }

    @Test
    void buildKeepsAdkAgentTypeAndExposesVeadkContract() {
        BaseMemoryService memoryService = mock(BaseMemoryService.class);
        BaseKnowledgebaseService knowledgebaseService = mock(BaseKnowledgebaseService.class);
        TestTool tool = new TestTool("lookup");

        Agent agent =
                Agent.builder()
                        .name("rag_agent")
                        .description("answers with tools")
                        .instruction("Use the configured tools.")
                        .model(new TestLlm("test-model"))
                        .tools(tool)
                        .knowledgebase(knowledgebaseService)
                        .longTermMemory(memoryService)
                        .autoSaveSession(true)
                        .build();

        assertThat(agent).isInstanceOf(LlmAgent.class);
        assertThat(agent.longTermMemoryService()).containsSame(memoryService);
        assertThat(agent.knowledgebaseService()).containsSame(knowledgebaseService);
        assertThat(agent.autoSaveSession()).isTrue();
        assertThat(agent.veadkModelName()).isEqualTo("test-model");

        AgentMetadataSnapshot snapshot = agent.metadataSnapshot();
        assertThat(snapshot.name()).isEqualTo("rag_agent");
        assertThat(snapshot.description()).isEqualTo("answers with tools");
        assertThat(snapshot.instructionSummary()).isEqualTo("Use the configured tools.");
        assertThat(snapshot.modelName()).isEqualTo("test-model");
        assertThat(snapshot.explicitToolNames()).containsExactly("lookup");
        assertThat(snapshot.autoToolNames()).containsExactly("loadKnowledgebase", "loadMemory");
        assertThat(snapshot.hasKnowledgebase()).isTrue();
        assertThat(snapshot.hasLongTermMemory()).isTrue();
        assertThat(snapshot.autoSaveSession()).isTrue();
    }

    @Test
    @SetEnvironmentVariable(key = "MODEL_AGENT_API_KEY", value = "test-api-key")
    @SetEnvironmentVariable(key = "MODEL_AGENT_API_BASE", value = "https://default.example.com/v1")
    void modelNameCreatesOpenAiCompatibleLlm() {
        Agent agent =
                Agent.builder()
                        .name("model_name_agent")
                        .modelName("doubao-seed-2-1-pro-260628")
                        .build();

        assertThat(agent.veadkModelName()).isEqualTo("doubao-seed-2-1-pro-260628");
        assertThat(agent.model()).isPresent();
        OpenAiCompatibleLlm llm =
                (OpenAiCompatibleLlm) agent.model().orElseThrow().model().orElseThrow();
        assertThat(llm.model()).isEqualTo("doubao-seed-2-1-pro-260628");
        assertThat(llm.config().getBaseUrl()).isEqualTo("https://default.example.com/v1");
    }

    @Test
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_KEY")
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_BASE")
    void modelApiKeyCreatesOpenAiCompatibleLlmWithoutEnv() {
        Agent agent =
                Agent.builder()
                        .name("explicit_key_agent")
                        .modelName("doubao-seed-2-1-pro-260628")
                        .modelApiKey("explicit-api-key")
                        .build();

        assertThat(agent.model()).isPresent();
        OpenAiCompatibleLlm llm =
                (OpenAiCompatibleLlm) agent.model().orElseThrow().model().orElseThrow();
        assertThat(llm.config().getApiKey()).isEqualTo("explicit-api-key");
        assertThat(llm.config().getBaseUrl()).isEqualTo(ArkLlmConfig.DEFAULT_API_BASE);
    }

    @Test
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_KEY")
    void explicitArkProviderCreatesArkLlm() {
        Agent agent =
                Agent.builder()
                        .name("explicit_ark_agent")
                        .modelProvider("ark")
                        .model("doubao-seed-2-1-pro-260628")
                        .modelApiKey("explicit-api-key")
                        .modelApiBase("https://ark.example.com/api/v3")
                        .build();

        assertThat(agent.veadkModelName()).isEqualTo("doubao-seed-2-1-pro-260628");
        ArkLlm arkLlm = (ArkLlm) agent.model().orElseThrow().model().orElseThrow();
        assertThat(arkLlm.config().getModelName()).isEqualTo("doubao-seed-2-1-pro-260628");
        assertThat(arkLlm.config().getApiKey()).isEqualTo("explicit-api-key");
        assertThat(arkLlm.config().getApiBase()).isEqualTo("https://ark.example.com/api/v3");
    }

    @Test
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_KEY")
    void openAiPrefixCreatesOpenAiCompatibleLlm() {
        Agent agent =
                Agent.builder()
                        .name("openai_prefix_agent")
                        .model("openai/gpt-4o")
                        .modelApiKey("openai-key")
                        .modelBaseUrl("https://api.openai.com/v1")
                        .build();

        assertThat(agent.veadkModelName()).isEqualTo("gpt-4o");
        OpenAiCompatibleLlm llm =
                (OpenAiCompatibleLlm) agent.model().orElseThrow().model().orElseThrow();
        assertThat(llm.model()).isEqualTo("gpt-4o");
        assertThat(llm.config().getApiKey()).isEqualTo("openai-key");
        assertThat(llm.config().getBaseUrl()).isEqualTo("https://api.openai.com/v1");
    }

    @Test
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_KEY")
    void explicitOpenAiProviderCreatesOpenAiCompatibleLlm() {
        Agent agent =
                Agent.builder()
                        .name("openai_provider_agent")
                        .modelProvider(ModelProvider.OPENAI_COMPATIBLE)
                        .model("anthropic/claude-sonnet-4")
                        .modelApiKey("litellm-key")
                        .modelBaseUrl("http://localhost:4000/v1")
                        .build();

        assertThat(agent.veadkModelName()).isEqualTo("anthropic/claude-sonnet-4");
        OpenAiCompatibleLlm llm =
                (OpenAiCompatibleLlm) agent.model().orElseThrow().model().orElseThrow();
        assertThat(llm.model()).isEqualTo("anthropic/claude-sonnet-4");
        assertThat(llm.config().getBaseUrl()).isEqualTo("http://localhost:4000/v1");
    }

    @Test
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_KEY")
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_BASE")
    void openAiCompatibleUsesDefaultBaseUrl() {
        Agent agent =
                Agent.builder()
                        .name("default_base_url_agent")
                        .modelProvider("openai")
                        .model("gpt-4o")
                        .modelApiKey("openai-key")
                        .build();

        OpenAiCompatibleLlm llm =
                (OpenAiCompatibleLlm) agent.model().orElseThrow().model().orElseThrow();
        assertThat(llm.config().getBaseUrl()).isEqualTo(ArkLlmConfig.DEFAULT_API_BASE);
    }

    @Test
    void explicitBaseLlmHasHighestPriorityOverProviderConfig() {
        TestLlm testLlm = new TestLlm("custom-model");

        Agent agent =
                Agent.builder()
                        .name("custom_model_priority_agent")
                        .modelProvider("openai")
                        .modelApiKey("openai-key")
                        .model(testLlm)
                        .build();

        assertThat(agent.veadkModelName()).isEqualTo("custom-model");
        assertThat(agent.model().orElseThrow().model().orElseThrow()).isSameAs(testLlm);
    }

    @Test
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_KEY")
    void modelNameAfterExplicitModelReconfiguresOpenAiCompatibleLlm() {
        Agent agent =
                Agent.builder()
                        .name("model_order_agent")
                        .model(new TestLlm("first-model"))
                        .modelApiKey("explicit-api-key")
                        .modelName("second-model")
                        .build();

        assertThat(agent.veadkModelName()).isEqualTo("second-model");
        assertThat(agent.model()).isPresent();
        assertThat(agent.model().orElseThrow().model().orElseThrow())
                .isInstanceOf(OpenAiCompatibleLlm.class);
        assertThat(agent.model().orElseThrow().model().orElseThrow().model())
                .isEqualTo("second-model");
    }

    @Test
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_KEY")
    void missingModelApiKeyFailsFastWhenAutoCreatingModel() {
        assertThatThrownBy(
                        () ->
                                Agent.builder()
                                        .name("missing_key_agent")
                                        .modelName("doubao-seed-2-1-pro-260628")
                                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODEL_AGENT_API_KEY");
    }

    @Test
    void adkBuilderMethodsKeepReturningVeadkBuilder() {
        Agent agent =
                Agent.builder()
                        .name("chain_agent")
                        .description("chain check")
                        .model(new TestLlm("chain-model"))
                        .instruction("stay typed")
                        .tools(new TestTool("chain_tool"))
                        .maxSteps(2)
                        .planning(true)
                        .knowledgebase(mock(BaseKnowledgebaseService.class))
                        .longTermMemory(mock(BaseMemoryService.class))
                        .autoSaveSession(false)
                        .build();

        assertThat(agent.name()).isEqualTo("chain_agent");
        assertThat(agent.veadkModelName()).isEqualTo("chain-model");
        assertThat(agent.metadataSnapshot().explicitToolNames()).containsExactly("chain_tool");
    }

    @Test
    void knowledgebaseAutoToolUsesPerAgentServiceAndIsCanonical() {
        Agent firstAgent =
                Agent.builder()
                        .name("first_agent")
                        .model(new TestLlm("first-model"))
                        .knowledgebase(knowledgebaseServiceReturning("first"))
                        .build();
        Agent secondAgent =
                Agent.builder()
                        .name("second_agent")
                        .model(new TestLlm("second-model"))
                        .knowledgebase(knowledgebaseServiceReturning("second"))
                        .build();

        BaseTool firstTool = findTool(firstAgent, "loadKnowledgebase");
        BaseTool secondTool = findTool(secondAgent, "loadKnowledgebase");
        ToolContext ctx = mock(ToolContext.class);

        assertThat(firstTool).isInstanceOf(LoadKnowledgebaseTool.class);
        assertThat(secondTool).isInstanceOf(LoadKnowledgebaseTool.class);
        assertThat(firstTool.customMetadata())
                .containsEntry(Agent.AUTO_TOOL_METADATA_KEY, true)
                .containsEntry(Agent.AUTO_TOOL_SOURCE_METADATA_KEY, "knowledgebase");
        assertThat(firstKnowledgeContent(firstTool, "same", ctx)).isEqualTo("first");
        assertThat(firstKnowledgeContent(secondTool, "same", ctx)).isEqualTo("second");
        assertThat(firstAgent.metadataSnapshot().explicitToolNames()).isEmpty();
        assertThat(firstAgent.metadataSnapshot().autoToolNames())
                .containsExactly("loadKnowledgebase");
    }

    @Test
    void longTermMemoryAutoInjectsOfficialLoadMemoryTool() {
        BaseMemoryService memoryService = mock(BaseMemoryService.class);

        Agent agent =
                Agent.builder()
                        .name("memory_agent")
                        .model(new TestLlm("memory-model"))
                        .longTermMemory(memoryService)
                        .build();
        BaseTool tool = findTool(agent, "loadMemory");

        assertThat(tool).isInstanceOf(LoadMemoryTool.class);
        assertThat(tool.customMetadata())
                .containsEntry(Agent.AUTO_TOOL_METADATA_KEY, true)
                .containsEntry(Agent.AUTO_TOOL_SOURCE_METADATA_KEY, "memory");
        assertThat(agent.metadataSnapshot().autoToolNames()).containsExactly("loadMemory");
    }

    @Test
    void shortTermMemoryIsStoredAndDoesNotInjectTool() {
        ShortTermMemory shortTermMemory = ShortTermMemory.local();

        Agent agent =
                Agent.builder()
                        .name("short_memory_agent")
                        .model(new TestLlm("short-memory-model"))
                        .shortTermMemory(shortTermMemory)
                        .build();

        assertThat(agent.shortTermMemory()).containsSame(shortTermMemory);
        assertThat(agent.metadataSnapshot().hasShortTermMemory()).isTrue();
        assertThat(agent.metadataSnapshot().autoToolNames()).isEmpty();
    }

    @Test
    void autoSaveSessionAddsCallbackOnce() {
        BaseMemoryService memoryService = mock(BaseMemoryService.class);

        Agent agent =
                Agent.builder()
                        .name("autosave_agent")
                        .model(new TestLlm("autosave-model"))
                        .longTermMemory(memoryService)
                        .autoSaveSession(true)
                        .build();

        assertThat(agent.afterAgentCallback())
                .filteredOn(SaveSessionToMemoryCallback.class::isInstance)
                .hasSize(1);
        assertThat(new Runner(agent).memoryService()).isSameAs(memoryService);
    }

    @Test
    void autoSaveSessionUsesConfiguredMemoryPolicy() {
        BaseMemoryService memoryService = mock(BaseMemoryService.class);
        SaveSessionToMemoryCallback.AutoSavePolicy policy =
                SaveSessionToMemoryCallback.AutoSavePolicy.builder()
                        .minEventsThreshold(2)
                        .minTimeThreshold(Duration.ofSeconds(3))
                        .build();

        Agent agent =
                Agent.builder()
                        .name("autosave_policy_agent")
                        .model(new TestLlm("autosave-policy-model"))
                        .longTermMemory(memoryService)
                        .autoSaveMemoryPolicy(policy)
                        .autoSaveSession(true)
                        .build();

        SaveSessionToMemoryCallback callback =
                (SaveSessionToMemoryCallback)
                        agent.afterAgentCallback().stream()
                                .filter(SaveSessionToMemoryCallback.class::isInstance)
                                .findFirst()
                                .orElseThrow();
        assertThat(callback.policy()).isSameAs(policy);
    }

    @Test
    void autoSaveSessionDoesNotDuplicateExplicitSaveCallback() {
        BaseMemoryService memoryService = mock(BaseMemoryService.class);
        SaveSessionToMemoryCallback saveCallback = new SaveSessionToMemoryCallback();

        Agent agent =
                Agent.builder()
                        .name("explicit_autosave_agent")
                        .model(new TestLlm("explicit-autosave-model"))
                        .longTermMemory(memoryService)
                        .afterAgentCallback(saveCallback)
                        .autoSaveSession(true)
                        .build();

        assertThat(agent.afterAgentCallback())
                .filteredOn(SaveSessionToMemoryCallback.class::isInstance)
                .hasSize(1);
        assertThat(agent.afterAgentCallback()).hasSize(1);
        assertThat(agent.afterAgentCallback().get(0)).isSameAs(saveCallback);
    }

    @Test
    void autoSaveSessionPreservesListAfterAgentCallbacks() {
        BaseMemoryService memoryService = mock(BaseMemoryService.class);
        Callbacks.AfterAgentCallback explicitCallback = callbackContext -> Maybe.empty();

        Agent agent =
                Agent.builder()
                        .name("list_callback_agent")
                        .model(new TestLlm("list-callback-model"))
                        .longTermMemory(memoryService)
                        .afterAgentCallback(List.of(explicitCallback))
                        .autoSaveSession(true)
                        .build();

        assertThat(agent.afterAgentCallback()).hasSize(2);
        assertThat(agent.afterAgentCallback().get(0)).isSameAs(explicitCallback);
        assertThat(agent.afterAgentCallback())
                .filteredOn(SaveSessionToMemoryCallback.class::isInstance)
                .hasSize(1);
    }

    @Test
    void unsupportedPythonOnlyOptionsFailFast() {
        assertThatThrownBy(() -> Agent.builder().name("unsupported_agent").runtime("codex"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("runtime is not supported");
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

    private static BaseTool findTool(Agent agent, String name) {
        return agent.canonicalTools().filter(tool -> tool.name().equals(name)).blockingFirst();
    }

    private static String firstKnowledgeContent(BaseTool tool, String query, ToolContext ctx) {
        Map<String, Object> result = tool.runAsync(Map.of("query", query), ctx).blockingGet();
        List<?> knowledges = (List<?>) result.get("knowledges");
        return (String) ((Map<?, ?>) knowledges.get(0)).get("content");
    }

    private static BaseKnowledgebaseService knowledgebaseServiceReturning(String content) {
        return query -> {
            SearchKnowledgebaseResponse response = new SearchKnowledgebaseResponse();
            response.setKnowledgebaseEntries(
                    List.of(new KnowledgebaseEntry(content, Map.of("query", query))));
            return Single.just(response);
        };
    }
}
