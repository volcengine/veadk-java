/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 * Licensed under the Apache License, Version 2.0.
 */
package com.volcengine.veadk.tools.builtin.lark;

import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StdioServerParameters;
import java.util.List;
import java.util.Map;

/** Creates the official Lark MCP toolset over a managed stdio process. */
public final class LarkToolset {

    private LarkToolset() {}

    /** Uses TOOL_LARK_ENDPOINT, TOOL_LARK_API_KEY and TOOL_LARK_TOKEN. */
    public static McpToolset create() {
        return create(
                System.getenv("TOOL_LARK_ENDPOINT"),
                System.getenv("TOOL_LARK_API_KEY"),
                System.getenv("TOOL_LARK_TOKEN"));
    }

    public static McpToolset create(String appId, String appSecret, String userAccessToken) {
        return new McpToolset(
                connectionParameters(appId, appSecret, userAccessToken).toServerParameters());
    }

    static StdioServerParameters connectionParameters(
            String appId, String appSecret, String userAccessToken) {
        return StdioServerParameters.builder()
                .command("npx")
                .args(
                        List.of(
                                "-y",
                                "@larksuiteoapi/lark-mcp",
                                "mcp",
                                "-a",
                                require(appId, "TOOL_LARK_ENDPOINT"),
                                "-s",
                                require(appSecret, "TOOL_LARK_API_KEY"),
                                "-u",
                                require(userAccessToken, "TOOL_LARK_TOKEN")))
                .env(Map.of())
                .build();
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be set");
        }
        return value.strip();
    }
}
