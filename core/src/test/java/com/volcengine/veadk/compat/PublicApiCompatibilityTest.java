/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.volcengine.veadk.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.google.adk.agents.BaseAgent;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.tools.ToolContext;
import com.volcengine.veadk.agent.SaveSessionPolicy;
import com.volcengine.veadk.agent.SaveSessionToMemoryCallback;
import com.volcengine.veadk.knowledgebase.BaseKnowledgebaseService;
import com.volcengine.veadk.memory.LongTermMemory;
import com.volcengine.veadk.memory.LongTermMemoryBackend;
import com.volcengine.veadk.memory.ShortTermMemory;
import com.volcengine.veadk.memory.ShortTermMemoryBackend;
import com.volcengine.veadk.memory.ShortTermMemoryProcessor;
import com.volcengine.veadk.model.ArkLlm;
import com.volcengine.veadk.runner.Runner;
import com.volcengine.veadk.tools.knowledgebase.LoadKnowledgebaseTool;
import com.volcengine.veadk.tools.sandbox.CodeSandboxToolset;
import com.volcengine.veadk.trace.OpenTelemetry;
import com.volcengine.veadk.utils.EnvUtil;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicApiCompatibilityTest {

    private static final List<String> BASELINE_PUBLIC_TYPES =
            List.of(
                    "com.volcengine.veadk.Version",
                    "com.volcengine.veadk.agent.SaveSessionToMemoryCallback",
                    "com.volcengine.veadk.integration.agentkit.AgentKitWrapper",
                    "com.volcengine.veadk.integration.vikingknowledgebase.KnowledgebaseEntry",
                    "com.volcengine.veadk.integration.vikingknowledgebase.VikingKnowledgebaseWrapper",
                    "com.volcengine.veadk.integration.vikingmemory.Message",
                    "com.volcengine.veadk.integration.vikingmemory.Metadata",
                    "com.volcengine.veadk.integration.vikingmemory.VikingMemoryWrapper",
                    "com.volcengine.veadk.integration.websearch.WebSearchWrapper",
                    "com.volcengine.veadk.knowledgebase.BaseKnowledgebaseService",
                    "com.volcengine.veadk.knowledgebase.SearchKnowledgebaseResponse",
                    "com.volcengine.veadk.knowledgebase.viking.VikingKnowledgebaseService",
                    "com.volcengine.veadk.memory.viking.VikingMemoryService",
                    "com.volcengine.veadk.model.ArkLlm",
                    "com.volcengine.veadk.runner.Runner",
                    "com.volcengine.veadk.tools.knowledgebase.LoadKnowledgebaseResponse",
                    "com.volcengine.veadk.tools.knowledgebase.LoadKnowledgebaseTool",
                    "com.volcengine.veadk.tools.sandbox.CodeSandboxToolset",
                    "com.volcengine.veadk.tools.sandbox.RunCodeTool",
                    "com.volcengine.veadk.tools.websearch.WebSearchTool",
                    "com.volcengine.veadk.trace.OpenTelemetry",
                    "com.volcengine.veadk.trace.exporter.AttributeRewritingSpanExporter",
                    "com.volcengine.veadk.trace.exporter.ExporterFactory",
                    "com.volcengine.veadk.trace.exporter.TLSExporter",
                    "com.volcengine.veadk.utils.EnvUtil",
                    "com.volcengine.veadk.utils.JSONUtil",
                    "com.volcengine.veadk.utils.ReadonlyContextAccessorUtil");

    @Test
    void baselinePublicTypesRemainLoadableAndPublic() throws ClassNotFoundException {
        for (String typeName : BASELINE_PUBLIC_TYPES) {
            Class<?> type = Class.forName(typeName);
            assertThat(Modifier.isPublic(type.getModifiers())).as(typeName).isTrue();
        }
    }

    @Test
    void runnerInheritanceAndConstructorsRemainCompatible() throws NoSuchMethodException {
        assertThat(com.google.adk.runner.Runner.class).isAssignableFrom(Runner.class);
        assertThat(Runner.class.getConstructor(BaseAgent.class)).isNotNull();
        assertThat(Runner.class.getConstructor(BaseAgent.class, String.class)).isNotNull();
        assertThat(Runner.class.getConstructor(BaseAgent.class, BaseMemoryService.class))
                .isNotNull();
        assertThat(
                        Runner.class.getConstructor(
                                BaseAgent.class, String.class, BaseMemoryService.class))
                .isNotNull();
    }

    @Test
    void runnerLegacyThreeArgumentNullCallRemainsSourceCompatible() {
        Runner runner = new Runner(mock(BaseAgent.class), "app", null);

        assertThat(runner.memoryService()).isInstanceOf(InMemoryMemoryService.class);
    }

    @Test
    void arkLlmConstructorsAndGenerationMethodsRemainCompatible() throws NoSuchMethodException {
        assertThat(ArkLlm.class.getConstructor(String.class)).isNotNull();
        assertThat(ArkLlm.class.getConstructor(String.class, String.class)).isNotNull();
        assertThat(ArkLlm.class.getConstructor(List.class)).isNotNull();
        assertThat(ArkLlm.class.getConstructor(List.class, String.class)).isNotNull();
        assertThat(ArkLlm.class.getConstructor(List.class, String.class, String.class)).isNotNull();
        assertThat(
                        ArkLlm.class.getConstructor(
                                List.class, String.class, String.class, String.class))
                .isNotNull();
        assertThat(ArkLlm.class.getMethod("fallbacks").getReturnType()).isEqualTo(List.class);
        assertThat(
                        ArkLlm.class
                                .getMethod("generateContent", LlmRequest.class, boolean.class)
                                .getReturnType())
                .isEqualTo(Flowable.class);
        assertThat(ArkLlm.class.getMethod("connect", LlmRequest.class).getReturnType())
                .isEqualTo(BaseLlmConnection.class);
        assertThat(ArkLlm.class.getSuperclass().getName())
                .isEqualTo("com.google.adk.models.BaseLlm");
        assertThat(LlmResponse.class).isNotNull();
    }

    @Test
    void knowledgebaseAndToolContractsRemainCompatible() throws NoSuchMethodException {
        assertThat(
                        BaseKnowledgebaseService.class
                                .getMethod("searchKnowledgebase", String.class)
                                .getReturnType())
                .isEqualTo(Single.class);
        assertThat(LoadKnowledgebaseTool.class.getConstructor(BaseKnowledgebaseService.class))
                .isNotNull();
        assertThat(
                        LoadKnowledgebaseTool.class
                                .getMethod("loadKnowledgebase", String.class, ToolContext.class)
                                .getReturnType())
                .isEqualTo(Single.class);
        assertThat(CodeSandboxToolset.class.getMethod("create")).isNotNull();
        assertThat(CodeSandboxToolset.class.getMethod("create", String.class)).isNotNull();
    }

    @Test
    void newLongTermMemoryFacadeKeepsAdkMemoryContract() throws NoSuchMethodException {
        assertThat(BaseMemoryService.class).isAssignableFrom(LongTermMemory.class);
        assertThat(LongTermMemory.class.getConstructor()).isNotNull();
        assertThat(LongTermMemory.class.getConstructor(String.class)).isNotNull();
        assertThat(LongTermMemory.class.getConstructor(LongTermMemoryBackend.class)).isNotNull();
        assertThat(
                        LongTermMemory.class
                                .getMethod("searchMemory", String.class, String.class, String.class)
                                .getReturnType())
                .isEqualTo(Single.class);
    }

    @Test
    void newShortTermMemoryFacadeKeepsAdkSessionContract() throws NoSuchMethodException {
        assertThat(com.google.adk.sessions.BaseSessionService.class)
                .isAssignableFrom(ShortTermMemory.class);
        assertThat(ShortTermMemory.class.getConstructor()).isNotNull();
        assertThat(ShortTermMemory.class.getConstructor(BaseSessionService.class)).isNotNull();
        assertThat(ShortTermMemory.class.getConstructor(ShortTermMemoryBackend.class)).isNotNull();
        assertThat(
                        ShortTermMemory.class.getConstructor(
                                ShortTermMemoryBackend.class, ShortTermMemoryProcessor.class))
                .isNotNull();
        assertThat(
                        ShortTermMemory.class
                                .getMethod(
                                        "createSession", String.class, String.class, String.class)
                                .getReturnType())
                .isEqualTo(io.reactivex.rxjava3.core.Single.class);
    }

    @Test
    void saveSessionCallbackKeepsLegacyConstructorAndAddsPolicyConfiguration()
            throws NoSuchMethodException {
        assertThat(SaveSessionToMemoryCallback.class.getConstructor()).isNotNull();
        assertThat(SaveSessionToMemoryCallback.class.getConstructor(SaveSessionPolicy.class))
                .isNotNull();
        assertThat(SaveSessionToMemoryCallback.class.getMethod("policy")).isNotNull();
    }

    @Test
    void environmentAndTelemetryEntryPointsRemainCompatible() throws NoSuchMethodException {
        List<String> environmentMethods =
                List.of(
                        "getAgentKitToolId",
                        "getAgentKitService",
                        "getAgentKitRegion",
                        "getAgentKitHost",
                        "getAgentApiKey",
                        "getAccessKey",
                        "getSecretKey",
                        "getTLSEndpoint",
                        "getTLSServiceName",
                        "getTLSRegion",
                        "getVikingMmemoryType",
                        "getCodeSandboxUrl");
        for (String methodName : environmentMethods) {
            assertThat(EnvUtil.class.getMethod(methodName).getReturnType())
                    .as(methodName)
                    .isEqualTo(String.class);
        }
        assertThat(OpenTelemetry.class.getMethod("initOpenTelemetry", List.class)).isNotNull();
    }
}
