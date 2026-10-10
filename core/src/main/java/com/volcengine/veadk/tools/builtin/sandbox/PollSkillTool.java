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
package com.volcengine.veadk.tools.builtin.sandbox;

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

/** A tool that fetches one Skills Sandbox task snapshot. */
public class PollSkillTool extends BaseTool implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PollSkillTool.class);

    private final SkillsSandboxA2aClient client;

    public PollSkillTool() {
        this(
                new AgentKitWrapper(
                        EnvUtil.getAgentKitManagementHost(),
                        EnvUtil.getAgentKitRegion(),
                        EnvUtil.getAccessKey(),
                        EnvUtil.getSecretKey()),
                HttpClient.newHttpClient());
    }

    PollSkillTool(AgentKitWrapper agentKitWrapper) {
        this(agentKitWrapper, HttpClient.newHttpClient());
    }

    PollSkillTool(AgentKitWrapper agentKitWrapper, HttpClient httpClient) {
        super("poll_skill", "Fetch a Skills Sandbox task status snapshot.", false);
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
                                                        "task_id",
                                                        Schema.builder()
                                                                .type("STRING")
                                                                .description(
                                                                        "The A2A task id returned"
                                                                            + " by invoke_skill.")
                                                                .build(),
                                                        "timeout",
                                                        Schema.builder()
                                                                .type("INTEGER")
                                                                .description(
                                                                        "The request timeout in"
                                                                            + " seconds. Defaults"
                                                                            + " to 1800.")
                                                                .build()))
                                        .required(ImmutableList.of("task_id"))
                                        .build())
                        .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
        return Single.fromCallable(() -> poll(args, context));
    }

    @Override
    public void close() {
        client.close();
    }

    private Map<String, Object> poll(Map<String, Object> args, ToolContext context) {
        try {
            String taskId = requireText((String) args.get("task_id"), "task_id");
            int timeout = SkillsSandboxA2aClient.timeoutSeconds(args.get("timeout"));
            return client.toMap(client.poll(taskId, context, timeout));
        } catch (Exception e) {
            logger.error("Failed to poll skills sandbox task: {}", e.getMessage());
            logger.debug("Failed to poll skills sandbox task", e);
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
