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

import com.google.adk.JsonBaseModel;
import com.google.adk.tools.mcp.McpSessionManager;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StreamableHttpServerParameters;
import com.volcengine.veadk.utils.EnvUtil;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Creates an ADK MCP toolset connected to Volcengine MCP Router. */
public final class McpRouterToolset {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(5);

    private McpRouterToolset() {}

    /** Creates a toolset from {@code TOOL_MCP_ROUTER_URL} and its optional API key. */
    public static McpToolset create() {
        return create(EnvUtil.getMcpRouterUrl(), EnvUtil.getOptionalMcpRouterApiKey());
    }

    /** Creates an unauthenticated toolset for the supplied MCP Router URL. */
    public static McpToolset create(String routerUrl) {
        return create(routerUrl, "");
    }

    /** Creates a toolset for the supplied URL and optional Bearer API key. */
    public static McpToolset create(String routerUrl, String apiKey) {
        return new McpToolset(connectionParameters(routerUrl, apiKey));
    }

    /** Creates a toolset that exposes only the named MCP Router tools. */
    public static McpToolset create(
            String routerUrl, String apiKey, List<String> allowedToolNames) {
        return new McpToolset(
                connectionParameters(routerUrl, apiKey),
                JsonBaseModel.getMapper(),
                requireToolNames(allowedToolNames));
    }

    static McpToolset create(McpSessionManager sessionManager, List<String> allowedToolNames) {
        return new McpToolset(
                Objects.requireNonNull(sessionManager, "sessionManager must be set."),
                JsonBaseModel.getMapper(),
                requireToolNames(allowedToolNames));
    }

    static StreamableHttpServerParameters connectionParameters(String routerUrl, String apiKey) {
        String resolvedUrl = requireText(routerUrl, "routerUrl must be set.");
        String resolvedApiKey = apiKey == null ? "" : apiKey.strip();
        Map<String, String> headers =
                resolvedApiKey.isEmpty()
                        ? Map.of()
                        : Map.of("Authorization", "Bearer " + resolvedApiKey);
        return StreamableHttpServerParameters.builder()
                .url(resolvedUrl)
                .headers(headers)
                .timeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .terminateOnClose(true)
                .build();
    }

    private static List<String> requireToolNames(List<String> toolNames) {
        return Objects.requireNonNull(toolNames, "allowedToolNames must be set.").stream()
                .map(toolName -> requireText(toolName, "allowed tool name must be set."))
                .toList();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
