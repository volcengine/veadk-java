package com.volcengine.veadk.tools.sandbox;

import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StreamableHttpServerParameters;
import com.volcengine.veadk.utils.EnvUtil;

/**
 * A toolset that connects to the Volcengine Code Sandbox service via MCP (Model Context Protocol).
 *
 * <p>This toolset allows the agent to execute code in a secure, isolated environment.
 * It wraps the upstream {@link McpToolset} with Volcengine-specific configuration logic.
 */
public class CodeSandboxToolset {

    /**
     * Creates a new MCP toolset connected to the Code Sandbox service.
     *
     * @return A configured McpToolset ready to be used by an Agent.
     * @throws IllegalStateException if the sandbox URL is not configured.
     */
    public static McpToolset create() {
        return create(EnvUtil.getCodeSandboxUrl());
    }

    /**
     * Creates a new MCP toolset connected to the Code Sandbox service with a specific URL.
     *
     * @param sandboxUrl The full URL of the Sandbox MCP server (e.g., "http://sandbox-api/v1/mcp").
     * @return A configured McpToolset.
     */
    public static McpToolset create(String sandboxUrl) {
        // Determine connection type based on URL scheme or conventions.
        // Assuming standard HTTP/SSE for now as per Python's 'url' parameter usage.

        // Using Google ADK's StreamableHttpServerParameters for SSE-based MCP connection.
        // If the Python version supports stdio, we might need a different builder, but HTTP is
        // standard for remote services.
        StreamableHttpServerParameters params =
                StreamableHttpServerParameters.builder().url(sandboxUrl).build();

        return new McpToolset(params);
    }

    // Prevent instantiation
    private CodeSandboxToolset() {}
}
