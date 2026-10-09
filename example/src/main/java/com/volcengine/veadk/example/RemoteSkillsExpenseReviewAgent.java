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
                                你是企业财务共享中心的报销预审助手，但你不能直接执行技能。
                                收到用户请求后，必须调用 execute_skills 工具，把用户的完整请求交给
                                Skills Sandbox 中的 Agent 处理。
                                即使用户只是询问当前有哪些技能，也必须先调用 execute_skills。
                                最终用中文输出，说明可报销项、需补材料项、需审批项和风险点。
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
                    我准备提交一笔报销，请你帮我预审并写一版提交说明：
                    1. 昨晚 22:40 从客户办公室打车回酒店，金额 96 元，有发票；
                    2. 客户晚餐 4 人一共 780 元，有发票，参与人包括 2 位客户和 2 位我方同事；
                    3. 给客户买了 680 元伴手礼，有发票，但还没写客户姓名。

                    请判断哪些能直接报销、哪些需要补审批或补材料、哪些可能不能报。
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
