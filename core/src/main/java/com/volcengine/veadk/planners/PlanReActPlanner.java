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
package com.volcengine.veadk.planners;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;

/** Prompt-based plan, tool action, and final answer separation; no native thinking support required. */
public final class PlanReActPlanner implements Planner {
    public static final String FINAL_ANSWER_TAG = "/*FINAL_ANSWER*/";
    private static final List<String> THOUGHT_TAGS =
            List.of("/*PLANNING*/", "/*REPLANNING*/", "/*REASONING*/", "/*ACTION*/");

    @Override
    public String buildPlanningInstruction(LlmRequest request) {
        return """
        First provide a concise numbered plan under /*PLANNING*/ using only available tools.
        Execute actions with actual function calls, preserving the declared tool arguments.
        Summarize observations briefly under /*REASONING*/ and actions under /*ACTION*/.
        If observations require a revised plan, place it under /*REPLANNING*/.
        Do not invent tools or retry a failed tool automatically.
        Return the final user-facing answer under /*FINAL_ANSWER*/.
        If the available information is insufficient, explain what information is needed.
        """;
    }

    @Override
    public LlmResponse processPlanningResponse(LlmResponse response) {
        if (response.content().isEmpty()) {
            return response;
        }
        List<Part> parts = new ArrayList<>();
        List<Part> textRun = new ArrayList<>();
        for (Part part : response.content().orElseThrow().parts().orElse(List.of())) {
            // Native model thoughts are opaque. Never reinterpret their text as a final answer.
            if (part.text().isEmpty() || part.thought().orElse(false)) {
                processTextRun(textRun, parts);
                textRun.clear();
                parts.add(part);
            } else if (response.partial().orElse(false)) {
                // Tags can straddle stream chunks. Only the complete final response is split.
                parts.add(part.toBuilder().thought(true).build());
            } else {
                textRun.add(part);
            }
        }
        processTextRun(textRun, parts);
        return response.toBuilder()
                .content(response.content().orElseThrow().toBuilder().parts(parts).build())
                .build();
    }

    private static void processTextRun(List<Part> textRun, List<Part> result) {
        StringBuilder joined = new StringBuilder();
        textRun.forEach(part -> joined.append(part.text().orElseThrow()));
        String text = joined.toString();
        int split = text.lastIndexOf(FINAL_ANSWER_TAG);
        if (split < 0) {
            boolean planning = THOUGHT_TAGS.stream().anyMatch(text.stripLeading()::startsWith);
            textRun.forEach(
                    part -> result.add(planning ? part.toBuilder().thought(true).build() : part));
            return;
        }
        int answerStart = split + FINAL_ANSWER_TAG.length();
        int offset = 0;
        for (Part part : textRun) {
            String fragment = part.text().orElseThrow();
            int end = offset + fragment.length();
            // Slice in the original Part to retain its metadata even when tags cross boundaries.
            if (offset < split) {
                result.add(
                        part.toBuilder()
                                .text(fragment.substring(0, Math.min(end, split) - offset))
                                .thought(true)
                                .build());
            }
            if (end > answerStart) {
                result.add(
                        part.toBuilder()
                                .text(fragment.substring(Math.max(offset, answerStart) - offset))
                                .thought(false)
                                .build());
            }
            offset = end;
        }
    }
}
