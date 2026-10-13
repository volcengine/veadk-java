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
package com.volcengine.veadk.tools.builtin.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.mcp.McpSessionManager;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StreamableHttpServerParameters;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

class McpRouterToolsetTest {

    @Test
    @SetEnvironmentVariable(key = "TOOL_MCP_ROUTER_URL", value = "https://router.test/mcp")
    @SetEnvironmentVariable(key = "TOOL_MCP_ROUTER_API_KEY", value = "router-key")
    void publicFactoriesCreateToolsetsWithoutOpeningSessions() {
        List<McpToolset> toolsets =
                List.of(
                        McpRouterToolset.create(),
                        McpRouterToolset.create("https://router.test/mcp"),
                        McpRouterToolset.create("https://router.test/mcp", "router-key"),
                        McpRouterToolset.create(
                                "https://router.test/mcp",
                                "router-key",
                                List.of("lookup_weather")));

        assertThat(toolsets).allMatch(McpToolset.class::isInstance);
        toolsets.forEach(McpToolset::close);
    }

    @Test
    void connectionParametersUseBearerAuthenticationAndSdkTimeouts() {
        StreamableHttpServerParameters parameters =
                McpRouterToolset.connectionParameters(" https://router.test/mcp ", "router-key");

        assertThat(parameters.url()).isEqualTo("https://router.test/mcp");
        assertThat(parameters.headers())
                .containsExactly(Map.entry("Authorization", "Bearer router-key"));
        assertThat(parameters.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(parameters.readTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(parameters.terminateOnClose()).isTrue();
    }

    @Test
    void blankApiKeyDoesNotSendAuthorizationHeader() {
        StreamableHttpServerParameters parameters =
                McpRouterToolset.connectionParameters("https://router.test/mcp", " ");

        assertThat(parameters.headers()).isEmpty();
    }

    @Test
    void blankUrlAndToolNameFailFast() {
        assertThatThrownBy(() -> McpRouterToolset.create(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("routerUrl");
        assertThatThrownBy(
                        () -> McpRouterToolset.create("https://router.test/mcp", "", List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed tool name");
    }

    @Test
    void mcpInitializationRequestCanBeSerializedWithManagedJacksonDependencies() throws Exception {
        McpSchema.InitializeRequest request =
                new McpSchema.InitializeRequest(
                        "2025-06-18",
                        McpSchema.ClientCapabilities.builder().build(),
                        new McpSchema.Implementation("veadk-java", "test"));

        String json = McpJsonDefaults.getMapper().writeValueAsString(request);

        assertThat(json)
                .contains("\"protocolVersion\":\"2025-06-18\"")
                .contains("\"name\":\"veadk-java\"");
    }

    @Test
    void allowlistedToolCanBeSelectedCalledAndClosed() {
        McpSessionManager sessionManager = mock(McpSessionManager.class);
        McpSyncClient session = mock(McpSyncClient.class);
        when(sessionManager.createSession()).thenReturn(session);
        when(session.listTools())
                .thenReturn(
                        new McpSchema.ListToolsResult(
                                List.of(tool("lookup_weather"), tool("delete_database")), null));
        when(session.callTool(
                        argThat(
                                request ->
                                        "lookup_weather".equals(request.name())
                                                && "Beijing"
                                                        .equals(request.arguments().get("city")))))
                .thenReturn(
                        McpSchema.CallToolResult.builder()
                                .addTextContent("Sunny, 25 C")
                                .isError(false)
                                .build());
        McpToolset toolset = McpRouterToolset.create(sessionManager, List.of("lookup_weather"));

        List<BaseTool> tools = toolset.getTools(null).toList().blockingGet();
        Map<String, Object> result =
                tools.get(0).runAsync(Map.of("city", "Beijing"), null).blockingGet();
        toolset.close();

        assertThat(tools).extracting(BaseTool::name).containsExactly("lookup_weather");
        assertThat(result).containsEntry("text_output", List.of(Map.of("text", "Sunny, 25 C")));
        verify(session).close();
    }

    private static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder()
                .name(name)
                .description("Test tool " + name)
                .inputSchema(
                        new McpSchema.JsonSchema(
                                "object",
                                Map.of("city", Map.of("type", "string")),
                                List.of("city"),
                                false,
                                null,
                                null))
                .build();
    }
}
