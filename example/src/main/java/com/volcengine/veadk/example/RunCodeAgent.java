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
package com.volcengine.veadk.example;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.volcengine.veadk.model.ArkLlm;
import com.volcengine.veadk.tools.sandbox.RunCodeTool;

/** AgentKit example that executes generated Python code in the bound Sandbox tool. */
public final class RunCodeAgent {

    private static final String DEFAULT_MODEL_ID = "doubao-seed-1-8-251228";
    private static final String MODEL_ID = resolveModelId();

    public static final BaseAgent ROOT_AGENT = createAgent();

    private RunCodeAgent() {}

    private static BaseAgent createAgent() {
        return LlmAgent.builder()
                .name("run_code_agent")
                .description("A Python coding assistant backed by the AgentKit Sandbox.")
                .instruction(
                        """
                        You are a Python coding assistant. You must use the run_code tool to solve
                        calculation and programming tasks. Prefer Python standard libraries, show
                        the executed result, and do not claim success unless the tool returns it.
                        """)
                .model(new ArkLlm(MODEL_ID))
                .tools(new RunCodeTool())
                .build();
    }

    private static String resolveModelId() {
        String configuredModel = System.getenv("MODEL_AGENT_NAME");
        return configuredModel == null || configuredModel.isBlank()
                ? DEFAULT_MODEL_ID
                : configuredModel;
    }
}
