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
package com.volcengine.veadk.example.skills;

import com.volcengine.veadk.Agent;
import com.volcengine.veadk.Runner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Demonstrates local skills with a realistic employee expense pre-review workflow. */
public class LocalSkillsExpenseReviewAgent {

    private static final String MODEL_NAME = "doubao-seed-2-1-pro-260628";

    public static void main(String[] args) {
        Path skillsRoot = resolveSkillsRoot();

        Agent agent =
                Agent.builder()
                        .name("expense_review_assistant")
                        .description(
                                "Pre-reviews employee expense claims against local policy skills.")
                        .instruction(
                                """
                                你是企业财务共享中心的报销预审助手。
                                当用户咨询报销、差旅、招待、礼品或发票问题时，先加载本地
                                expense-policy-reviewer skill，再基于其中的规则回答。
                                不要编造未在 skill 中出现的公司制度；遇到缺失信息要明确列出。
                                输出中文，语气专业、清晰、可直接用于提交报销说明。
                                """)
                        .modelName(MODEL_NAME)
                        .skills(skillsRoot)
                        .skillsMode("local")
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

            System.out.println("Local skills root: " + skillsRoot);
            System.out.println("User request:");
            System.out.println(prompt);
            System.out.println("Agent answer:");
            System.out.println(runner.run(prompt));
        } finally {
            agent.close().blockingAwait();
        }
    }

    private static Path resolveSkillsRoot() {
        List<Path> candidates =
                List.of(
                        Path.of("example", "src", "main", "resources", "skills"),
                        Path.of("src", "main", "resources", "skills"));
        return candidates.stream()
                .filter(Files::isDirectory)
                .findFirst()
                .map(path -> path.toAbsolutePath().normalize())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Cannot find local skills root. Run from the repository"
                                                + " root or from the example module."));
    }
}
