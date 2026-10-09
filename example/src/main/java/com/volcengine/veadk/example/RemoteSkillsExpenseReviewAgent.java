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

import com.volcengine.veadk.Agent;
import com.volcengine.veadk.Runner;
import com.volcengine.veadk.tools.sandbox.ExecuteSkillsTool;

/** Demonstrates delegating an expense pre-review workflow to a remote Skills Sandbox. */
public class RemoteSkillsExpenseReviewAgent {

    private static final String MODEL_NAME = "doubao-seed-2-1-pro-260628";

    public static void main(String[] args) {
        String skillSpaceId = requiredEnv("SKILL_SPACE_ID");

        Agent agent =
                Agent.builder()
                        .name("remote_expense_review_assistant")
                        .description(
                                "Delegates employee expense pre-review requests to a remote skill"
                                        + " space.")
                        .instruction(
                                """
                                你是智能助手，但你不能直接执行技能。
                                收到用户请求后，必须调用 execute_skills 工具，把用户的完整请求交给
                                Skills Sandbox 中的 Agent 处理。
                                即使用户只是询问当前有哪些技能，也必须先调用 execute_skills。
                                最终用中文输出。
                                """)
                        .modelName(MODEL_NAME)
                        .skills(skillSpaceId)
                        .skillsMode("skills_sandbox")
                        .tools(new ExecuteSkillsTool())
                        .build();

        try {
            Runner runner = new Runner(agent);
            String prompt =
"""
使用技能搜索一下新能源汽车品牌
""";

            System.out.println("Remote skill space: " + skillSpaceId);
            System.out.println("User request:");
            System.out.println(prompt);
            System.out.println("Agent answer:");
            System.out.println(runner.run(prompt));
        } finally {
            agent.close().blockingAwait();
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required configuration: "
                            + name
                            + ". Please configure the environment variable before startup.");
        }
        return value;
    }
}
