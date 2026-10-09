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
package com.volcengine.veadk.tools.sandbox;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.volcengine.veadk.integration.agentkit.AgentKitWrapper;
import com.volcengine.veadk.utils.EnvUtil;
import io.reactivex.rxjava3.core.Single;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A tool that creates a non-blocking Skills Sandbox task. */
public class InvokeSkillTool extends BaseTool implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(InvokeSkillTool.class);

    private final SkillsSandboxA2aClient client;

    public InvokeSkillTool() {
        this(
                new AgentKitWrapper(
                        EnvUtil.getAgentKitManagementHost(),
                        EnvUtil.getAgentKitRegion(),
                        EnvUtil.getAccessKey(),
                        EnvUtil.getSecretKey()),
                HttpClient.newHttpClient());
    }

    InvokeSkillTool(AgentKitWrapper agentKitWrapper) {
        this(agentKitWrapper, HttpClient.newHttpClient());
    }

    InvokeSkillTool(AgentKitWrapper agentKitWrapper, HttpClient httpClient) {
        super("invoke_skill", "Create a non-blocking task in a remote Skills Sandbox.", false);
        this.client = new SkillsSandboxA2aClient(agentKitWrapper, httpClient);
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(
                FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(
                                Schema.builder()
                                        .type("OBJECT")
                                        .properties(
                                                ImmutableMap.of(
                                                        "workflow_prompt",
                                                        Schema.builder()
                                                                .type("STRING")
                                                                .description(
                                                                        "The user request or"
                                                                            + " workflow"
                                                                            + " instruction to run"
                                                                            + " in the Skills"
                                                                            + " Sandbox.")
                                                                .build(),
                                                        "timeout",
                                                        Schema.builder()
                                                                .type("INTEGER")
                                                                .description(
                                                                        "The request timeout in"
                                                                            + " seconds. Defaults"
                                                                            + " to 1800.")
                                                                .build()))
                                        .required(ImmutableList.of("workflow_prompt"))
                                        .build())
                        .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
        return Single.fromCallable(() -> invoke(args, context));
    }

    @Override
    public void close() {
        client.close();
    }

    private Map<String, Object> invoke(Map<String, Object> args, ToolContext context) {
        try {
            String workflowPrompt =
                    requireText((String) args.get("workflow_prompt"), "workflow_prompt");
            int timeout = SkillsSandboxA2aClient.timeoutSeconds(args.get("timeout"));
            return client.toMap(client.invoke(workflowPrompt, context, timeout));
        } catch (Exception e) {
            logger.error("Failed to invoke skills sandbox task: {}", e.getMessage());
            logger.debug("Failed to invoke skills sandbox task", e);
            return ImmutableMap.of("error", e.getMessage());
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be set.");
        }
        return value;
    }
}
