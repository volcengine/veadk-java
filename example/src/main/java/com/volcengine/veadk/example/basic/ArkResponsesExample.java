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
package com.volcengine.veadk.example.basic;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.volcengine.veadk.model.ArkLlm;
import com.volcengine.veadk.model.ArkLlmConfig;
import com.volcengine.veadk.model.ArkResponsesSession;
import com.volcengine.veadk.utils.JSONUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Two live Responses calls with JSON Schema output and explicit session continuation. */
public class ArkResponsesExample {
    public static void main(String[] args) throws Exception {
        boolean stream = args.length > 0 && args[0].equals("stream");
        String model = System.getenv("VEADK_TEST_MODEL");
        if (model == null || model.isBlank())
            throw new IllegalArgumentException("Set VEADK_TEST_MODEL.");
        var generation =
                GenerateContentConfig.builder()
                        .temperature(0.25f)
                        .maxOutputTokens(256)
                        .responseMimeType("application/json")
                        .responseJsonSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("value", Map.of("type", "integer")),
                                        "required",
                                        List.of("value"),
                                        "additionalProperties",
                                        false))
                        .build();
        List<Content> history = new ArrayList<>();
        LlmResponse previous = null;
        try (var llm =
                new ArkLlm(
                        ArkLlmConfig.builder()
                                .modelName(model)
                                .api(ArkLlmConfig.Api.RESPONSES)
                                .thinking("disabled")
                                .build())) {
            for (int value : List.of(17, 29)) {
                history.add(
                        Content.builder()
                                .role("user")
                                .parts(Part.fromText("Return JSON with value equal to " + value))
                                .build());
                var request =
                        LlmRequest.builder()
                                .model(model)
                                .config(generation)
                                .contents(List.copyOf(history))
                                .build();
                if (previous != null) request = ArkResponsesSession.continueFrom(request, previous);
                var responses =
                        llm.generateContent(request, stream)
                                .timeout(45, TimeUnit.SECONDS)
                                .toList()
                                .blockingGet();
                previous = responses.get(responses.size() - 1);
                if (previous.partial().orElse(false)
                        || ArkResponsesSession.responseId(previous).isEmpty()) {
                    throw new IllegalStateException("Expected a completed response with an ID.");
                }
                String json =
                        previous.content().orElseThrow().parts().orElseThrow().stream()
                                .flatMap(part -> part.text().stream())
                                .collect(java.util.stream.Collectors.joining());
                if (JSONUtil.parseJson(json).path("value").asInt() != value) {
                    throw new IllegalStateException("Unexpected structured response.");
                }
                if (previous.usageMetadata().orElseThrow().totalTokenCount().orElseThrow() <= 0) {
                    throw new IllegalStateException("Expected token usage.");
                }
                history.add(previous.content().orElseThrow());
            }
        }
        System.out.println(
                "PASS Responses "
                        + (stream ? "stream" : "nonstream")
                        + ": two JSON Schema turns with response IDs and usage");
    }
}
