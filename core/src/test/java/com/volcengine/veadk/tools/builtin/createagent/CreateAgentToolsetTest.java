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
package com.volcengine.veadk.tools.builtin.createagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.adk.agents.InvocationContext;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.volcengine.veadk.Agent;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class CreateAgentToolsetTest {

    @Test
    @SuppressWarnings("unchecked")
    void collectsResourcesCreatesAgentAndTransfersInSameSession() {
        BaseTool mountedTool = new NoopTool();
        ResourceSource source =
                new ResourceSource() {
                    @Override
                    public String name() {
                        return "static";
                    }

                    @Override
                    public CompletableFuture<List<ResourceDescriptor>> collect(
                            List<String> keywords) {
                        assertThat(keywords).containsExactly("AgentKit", "research");
                        return CompletableFuture.completedFuture(
                                List.of(
                                        new ResourceDescriptor(
                                                "veadk_tool:noop",
                                                "tool",
                                                "noop",
                                                "No-op tool",
                                                mountedTool)));
                    }
                };
        CreateAgentToolset toolset = new CreateAgentToolset(List.of(source));
        Agent parent = Agent.builder().name("main").model(new EmptyLlm()).tools(toolset).build();
        ToolContext context = context(parent, "invocation-1");
        List<BaseTool> tools = toolset.getTools(null).toList().blockingGet();

        Map<String, Object> collected =
                tools.get(0)
                        .runAsync(
                                Map.of(
                                        "skill_hub_keywords",
                                        List.of(" AgentKit ", "research", "AgentKit")),
                                context)
                        .blockingGet();
        String collectionId = (String) collected.get("collection_id");
        Map<String, Object> createArgs =
                Map.of(
                        "collection_id",
                        collectionId,
                        "agents",
                        List.of(
                                Map.of(
                                        "name", "researcher",
                                        "task", "Research the requested topic",
                                        "root_node", "pipeline",
                                        "nodes",
                                                List.of(
                                                        Map.of(
                                                                "id",
                                                                "worker",
                                                                "type",
                                                                "llm",
                                                                "instruction",
                                                                "Research" + " carefully.",
                                                                "resources",
                                                                List.of("veadk_tool:noop")),
                                                        Map.of(
                                                                "id", "writer",
                                                                "type", "llm",
                                                                "instruction",
                                                                        "Write the" + " result."),
                                                        Map.of(
                                                                "id", "pipeline",
                                                                "type", "sequential",
                                                                "children",
                                                                        List.of(
                                                                                "worker",
                                                                                "writer"))))),
                        "handoff_to",
                        "researcher");
        Map<String, Object> created = tools.get(1).runAsync(createArgs, context).blockingGet();

        String runtimeName = (String) created.get("handoff_to");
        assertThat(runtimeName).startsWith("researcher_");
        assertThat(context.actions().transferToAgent()).contains(runtimeName);
        assertThat(parent.findAgent(runtimeName)).isPresent();
        Map<String, Object> result = ((List<Map<String, Object>>) created.get("results")).get(0);
        assertThat(result)
                .containsEntry("status", "completed")
                .containsEntry("root_type", "sequential");
        assertThat(tools.get(1).runAsync(createArgs, context).blockingGet()).isEqualTo(created);

        toolset.close();
        assertThat(parent.findAgent(runtimeName)).isEmpty();
        parent.close().blockingAwait();
    }

    @Test
    void rejectsUnknownOrCrossInvocationCollection() {
        CreateAgentToolset toolset = new CreateAgentToolset();
        Agent parent = Agent.builder().name("main").model(new EmptyLlm()).build();
        ToolContext first = context(parent, "invocation-1");
        ToolContext second = context(parent, "invocation-2");
        List<BaseTool> tools = toolset.getTools(null).toList().blockingGet();
        String collectionId =
                (String) tools.get(0).runAsync(Map.of(), first).blockingGet().get("collection_id");

        assertThatThrownBy(
                        () ->
                                tools.get(1)
                                        .runAsync(
                                                Map.of(
                                                        "collection_id",
                                                        collectionId,
                                                        "agents",
                                                        List.of(blueprint()),
                                                        "handoff_to",
                                                        "child"),
                                                second)
                                        .blockingGet())
                .hasMessage(
                        "collection_id belongs to a different invocation or session. Call"
                                + " collect_resources in the current session.");
        assertThatThrownBy(
                        () ->
                                tools.get(1)
                                        .runAsync(
                                                Map.of(
                                                        "collection_id", "missing",
                                                        "agents", List.of(blueprint()),
                                                        "handoff_to", "child"),
                                                first)
                                        .blockingGet())
                .hasMessage(
                        "Unknown or expired collection_id 'missing'. Call collect_resources"
                                + " first.");

        toolset.close();
        parent.close().blockingAwait();
    }

    private static Map<String, Object> blueprint() {
        return Map.of(
                "name", "child",
                "task", "Do work",
                "root_node", "worker",
                "nodes", List.of(Map.of("id", "worker", "type", "llm", "instruction", "Work")));
    }

    private static ToolContext context(Agent agent, String invocationId) {
        InMemorySessionService sessions = new InMemorySessionService();
        Session session = sessions.createSession("app", "user", null, "session").blockingGet();
        InvocationContext invocation =
                InvocationContext.builder()
                        .agent(agent)
                        .invocationId(invocationId)
                        .session(session)
                        .sessionService(sessions)
                        .artifactService(new InMemoryArtifactService())
                        .memoryService(new InMemoryMemoryService())
                        .build();
        return ToolContext.builder(invocation).build();
    }

    private static final class EmptyLlm extends BaseLlm {
        private EmptyLlm() {
            super("empty");
        }

        @Override
        public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
            return Flowable.empty();
        }

        @Override
        public BaseLlmConnection connect(LlmRequest llmRequest) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NoopTool extends BaseTool {
        private NoopTool() {
            super("noop", "No-op", false);
        }

        @Override
        public java.util.Optional<com.google.genai.types.FunctionDeclaration> declaration() {
            return java.util.Optional.empty();
        }

        @Override
        public io.reactivex.rxjava3.core.Single<Map<String, Object>> runAsync(
                Map<String, Object> args, ToolContext context) {
            return io.reactivex.rxjava3.core.Single.just(Map.of());
        }
    }
}
