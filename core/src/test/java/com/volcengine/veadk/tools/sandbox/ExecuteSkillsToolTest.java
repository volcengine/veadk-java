package com.volcengine.veadk.tools.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.volcengine.veadk.integration.agentkit.AgentKitWrapper;
import com.volcengine.veadk.integration.agentkit.AgentKitWrapper.AgentKitSession;
import com.volcengine.veadk.utils.EnvUtil;
import com.volcengine.veadk.utils.JSONUtil;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class ExecuteSkillsToolTest {

    @Test
    void declarationContainsWorkflowPrompt() {
        ExecuteSkillsTool tool = new ExecuteSkillsTool(mock(AgentKitWrapper.class));

        Optional<FunctionDeclaration> declarationOpt = tool.declaration();

        assertThat(declarationOpt).isPresent();
        FunctionDeclaration declaration = declarationOpt.get();
        assertThat(declaration.name()).isEqualTo(Optional.of("execute_skills"));
        assertThat(declaration.parameters().orElseThrow().properties().orElseThrow())
                .containsKey("workflow_prompt");
    }

    @Test
    void runAsyncPostsA2aMessageAndReturnsArtifactText() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server =
                startA2aServer(
                        requests,
                        List.of(
                                """
                                {"result":{"kind":"task","id":"task-1","status":{"state":"working"}}}
                                """,
                                """
                                {"result":{"kind":"task","id":"task-1","status":{"state":"completed"},"artifacts":[{"parts":[{"kind":"text","text":"审批建议已生成"}]}]}}
                                """));
        try (MockedStatic<EnvUtil> envUtilMock = mockStatic(EnvUtil.class)) {
            envUtilMock.when(EnvUtil::getAgentKitSkillsToolId).thenReturn("skills-tool");

            AgentKitWrapper wrapper = mock(AgentKitWrapper.class);
            when(wrapper.ensureSessionEndpoint(
                            eq("skills-tool"),
                            eq("remote_agent_user-1_session-1"),
                            anyInt(),
                            anyBoolean()))
                    .thenReturn(
                            AgentKitSession.of(
                                    "session-id",
                                    "remote_agent_user-1_session-1",
                                    "Ready",
                                    "http://127.0.0.1:" + server.getAddress().getPort(),
                                    null,
                                    "2026-10-09T00:00:00Z"));
            ExecuteSkillsTool tool = new ExecuteSkillsTool(wrapper);

            Map<String, Object> result =
                    tool.runAsync(
                                    ImmutableMap.of("workflow_prompt", "帮我预审这笔报销", "timeout", 3),
                                    toolContext())
                            .blockingGet();

            assertThat(result).containsEntry("result", "审批建议已生成");
            assertThat(requests).hasSize(2);
            assertThat(requests.get(0).path("method").asText()).isEqualTo("message/send");
            assertThat(
                            requests.get(0)
                                    .path("params")
                                    .path("message")
                                    .path("parts")
                                    .get(0)
                                    .path("text")
                                    .asText())
                    .isEqualTo("帮我预审这笔报销");
            assertThat(requests.get(0).path("params").path("metadata").path("user_id").asText())
                    .isEqualTo("user-1");
            assertThat(requests.get(0).path("params").path("metadata").path("session_id").asText())
                    .isEqualTo("session-1");
            assertThat(requests.get(1).path("method").asText()).isEqualTo("tasks/get");
            assertThat(requests.get(1).path("params").path("id").asText()).isEqualTo("task-1");
            verify(wrapper)
                    .ensureSessionEndpoint(
                            eq("skills-tool"),
                            eq("remote_agent_user-1_session-1"),
                            eq(1800),
                            eq(true));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runAsyncRejectsUnsupportedEnvVars() {
        ExecuteSkillsTool tool = new ExecuteSkillsTool(mock(AgentKitWrapper.class));

        Map<String, Object> result =
                tool.runAsync(
                                ImmutableMap.of(
                                        "workflow_prompt",
                                        "do work",
                                        "env_vars",
                                        ImmutableMap.of("A", "B")),
                                toolContext())
                        .blockingGet();

        assertThat(result.get("error").toString()).contains("env_vars is not supported");
    }

    private static HttpServer startA2aServer(List<JsonNode> requests, List<String> responses)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/a2a",
                exchange -> {
                    int index = requests.size();
                    requests.add(JSONUtil.parseJson(exchange.getRequestBody().readAllBytes()));
                    respond(exchange, responses.get(Math.min(index, responses.size() - 1)));
                });
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static ToolContext toolContext() {
        ToolContext context = mock(ToolContext.class);
        when(context.agentName()).thenReturn("remote_agent");
        when(context.userId()).thenReturn("user-1");
        when(context.sessionId()).thenReturn("session-1");
        return context;
    }
}
