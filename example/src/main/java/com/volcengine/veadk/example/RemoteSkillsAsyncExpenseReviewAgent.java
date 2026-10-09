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

import com.google.adk.tools.skills.SkillToolset;
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.Runner;
import com.volcengine.veadk.skills.VeSkillSource;
import com.volcengine.veadk.tools.sandbox.InvokeSkillTool;
import com.volcengine.veadk.tools.sandbox.PollSkillTool;

/** Demonstrates remote skill discovery with non-blocking Skills Sandbox execution. */
public class RemoteSkillsAsyncExpenseReviewAgent {

    private static final String MODEL_NAME = "doubao-seed-2-1-pro-260628";

    public static void main(String[] args) {
        String skillSourceId = requiredEnv("SKILL_SOURCE_ID", "SKILL_SPACE_ID");

        Agent agent =
                Agent.builder()
                        .name("remote_async_expense_review_assistant")
                        .description(
                                "Uses remote skills and non-blocking Skills Sandbox tasks for"
                                        + " expense pre-review.")
                        .instruction(
                                """
                                你是企业费用预审助手。
                                先使用远端技能信息判断可以处理的事项。
                                对需要远端执行的预审任务，调用 invoke_skill 创建任务，再用 poll_skill 查询任务状态。
                                如果任务还没有完成，继续查询直到获得明确结果。
                                最终用中文输出费用风险点、需要补充的材料和审批建议。
                                """)
                        .modelName(MODEL_NAME)
                        .tools(
                                new SkillToolset(new VeSkillSource(skillSourceId)),
                                new InvokeSkillTool(),
                                new PollSkillTool())
                        .build();

        try {
            Runner runner = new Runner(agent);
            String prompt =
                    """
                    请预审报销单 ER-2026-1019：
                    申请人张宁从上海到北京参加客户续约会议，提交高铁票 553 元、两晚酒店 1260 元、
                    市内交通 186 元、客户晚餐 980 元。报销说明里写明客户为华北区重点客户，
                    但餐饮发票抬头和会议纪要还没有上传。请使用可用技能给出审批建议。
                    """;

            System.out.println("Remote skill source: " + skillSourceId);
            System.out.println("User request:");
            System.out.println(prompt);
            System.out.println("Agent answer:");
            System.out.println(runner.run(prompt));
        } finally {
            agent.close().blockingAwait();
        }
    }

    private static String requiredEnv(String primaryName, String fallbackName) {
        String value = System.getenv(primaryName);
        if (value == null || value.isBlank()) {
            value = System.getenv(fallbackName);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required configuration: "
                            + primaryName
                            + " or "
                            + fallbackName
                            + ". Please configure one environment variable before startup.");
        }
        return value;
    }
}
