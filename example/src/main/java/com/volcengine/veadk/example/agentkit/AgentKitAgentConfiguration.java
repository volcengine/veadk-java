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
package com.volcengine.veadk.example.agentkit;

import com.google.adk.agents.BaseAgent;
import com.google.adk.tools.BaseTool;
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.model.ArkLlm;
import com.volcengine.veadk.model.ArkLlmConfig;
import com.volcengine.veadk.tools.builtin.sandbox.RunCodeTool;
import com.volcengine.veadk.tools.builtin.webfetch.WebFetchTool;
import com.volcengine.veadk.tools.builtin.websearch.WebSearchTool;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Configures a built-in Agent or loads an existing public static ROOT_AGENT. */
public final class AgentKitAgentConfiguration implements AutoCloseable {
    private final BaseAgent agent;
    private final ArkLlm ownedModel;
    private final AtomicBoolean closed = new AtomicBoolean();

    private AgentKitAgentConfiguration(BaseAgent agent, ArkLlm ownedModel) {
        this.agent = agent;
        this.ownedModel = ownedModel;
    }

    public BaseAgent agent() {
        return agent;
    }

    public static AgentKitAgentConfiguration fromEnvironment(Map<String, String> env) {
        String className = env.get("AGENTKIT_AGENT_CLASS");
        if (className != null && !className.isBlank()) {
            return new AgentKitAgentConfiguration(loadRootAgent(className), null);
        }
        var tools =
                Arrays.stream(env.getOrDefault("AGENTKIT_TOOLS", "").split(","))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .distinct()
                        .toList();
        if (!Set.of("web_search", "web_fetch", "run_code").containsAll(tools)) {
            throw new IllegalArgumentException(
                    "Supported Java tools: web_search, web_fetch, run_code");
        }
        String modelName = env.get("MODEL_AGENT_NAME");
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException(
                    "Set MODEL_AGENT_NAME to an accessible Ark model ID");
        }
        ArkLlm model =
                new ArkLlm(
                        ArkLlmConfig.builder()
                                .modelName(modelName)
                                .apiKey(env.get("MODEL_AGENT_API_KEY"))
                                .apiBase(env.get("MODEL_AGENT_API_BASE"))
                                .build());
        try {
            var builder =
                    Agent.builder()
                            .name(env.getOrDefault("AGENT_NAME", "agentkit_java"))
                            .description(
                                    env.getOrDefault(
                                            "AGENT_DESCRIPTION", "An AgentKit Java agent."))
                            .instruction(
                                    env.getOrDefault(
                                            "MODEL_AGENT_INSTRUCTION",
                                            "Answer the user's question concisely."))
                            .model(model);
            var selectedTools = new ArrayList<BaseTool>();
            for (String tool : tools) {
                if (tool.equals("web_search")) selectedTools.add(new WebSearchTool());
                else if (tool.equals("web_fetch")) selectedTools.add(new WebFetchTool());
                else selectedTools.add(new RunCodeTool());
            }
            return new AgentKitAgentConfiguration(builder.tools(selectedTools).build(), model);
        } catch (RuntimeException failure) {
            model.close();
            throw failure;
        }
    }

    private static BaseAgent loadRootAgent(String className) {
        if (!className.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*")) {
            throw new IllegalArgumentException("AGENTKIT_AGENT_CLASS must be a Java class name");
        }
        try {
            var field = Class.forName(className).getField("ROOT_AGENT");
            if (!Modifier.isStatic(field.getModifiers())
                    || !BaseAgent.class.isAssignableFrom(field.getType())) {
                throw new IllegalArgumentException(
                        "ROOT_AGENT must be a public static BaseAgent field");
            }
            if (!(field.get(null) instanceof BaseAgent root)) {
                throw new IllegalArgumentException("ROOT_AGENT must not be null");
            }
            return root;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException(
                    "Cannot load public static ROOT_AGENT from " + className, failure);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            agent.close().blockingAwait(5, TimeUnit.SECONDS);
        } finally {
            if (ownedModel != null) ownedModel.close();
        }
    }
}
