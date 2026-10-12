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
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.volcengine.veadk.model.ArkLlm;
import com.volcengine.veadk.model.ArkLlmConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runnable, opt-in model verification. Requires VEADK_TEST_MODEL and MODEL_AGENT_API_KEY. */
public class ArkInferenceExample {
    @Schema(description = "Look up a sample value by key")
    public static Map<String, Object> lookup(
            @Schema(name = "key", description = "Sample key: alpha or beta") String key) {
        return Map.of("key", key, "value", key.equals("alpha") ? 17 : 29);
    }

    public static void main(String[] args) {
        boolean stream = args.length > 0 && args[0].equals("stream");
        String model = System.getenv("VEADK_TEST_MODEL");
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Set VEADK_TEST_MODEL to an accessible model ID.");
        }
        var config =
                GenerateContentConfig.builder()
                        .temperature(0.25f)
                        .topP(0.75f)
                        .maxOutputTokens(256)
                        .build();
        var tool = FunctionTool.create(ArkInferenceExample.class, "lookup");
        List<Content> history = new ArrayList<>();
        int toolCalls = 0;
        int totalTokens = 0;
        try (ArkLlm llm =
                new ArkLlm(ArkLlmConfig.builder().modelName(model).thinking("disabled").build())) {
            for (String key : List.of("alpha", "beta")) {
                history.add(
                        Content.fromParts(
                                Part.fromText(
                                        "Call lookup exactly once with key "
                                                + key
                                                + ", then report its value in one sentence. Do not"
                                                + " guess the value.")));
                boolean completed = false;
                int callsBeforeTurn = toolCalls;
                for (int attempt = 0; attempt < 3; attempt++) {
                    var request =
                            LlmRequest.builder()
                                    .model(model)
                                    .config(config)
                                    .contents(List.copyOf(history))
                                    .tools(Map.of(tool.name(), tool))
                                    .build();
                    List<LlmResponse> responses =
                            llm.generateContent(request, stream)
                                    .timeout(45, TimeUnit.SECONDS)
                                    .toList()
                                    .blockingGet();
                    if (responses.isEmpty()) throw new IllegalStateException("No model response.");
                    for (LlmResponse partial : responses) {
                        if (partial.partial().orElse(false)
                                && partial.usageMetadata().isPresent()) {
                            throw new IllegalStateException(
                                    "Partial output must not duplicate usage.");
                        }
                    }
                    LlmResponse response = responses.get(responses.size() - 1);
                    if (response.partial().orElse(false)) {
                        throw new IllegalStateException("Missing completed response.");
                    }
                    int tokens =
                            response.usageMetadata().orElseThrow().totalTokenCount().orElseThrow();
                    if (tokens <= 0)
                        throw new IllegalStateException("Expected positive token usage.");
                    totalTokens += tokens;
                    Content content = response.content().orElseThrow();
                    history.add(content);
                    List<Part> results = new ArrayList<>();
                    for (Part part : content.parts().orElse(List.of())) {
                        if (part.functionCall().isEmpty()) continue;
                        var call = part.functionCall().orElseThrow();
                        String id = call.id().orElseThrow();
                        if (id.isBlank()
                                || !call.name().orElseThrow().equals("lookup")
                                || !key.equals(call.args().orElseThrow().get("key"))) {
                            throw new IllegalStateException("Unexpected tool call.");
                        }
                        results.add(
                                Part.builder()
                                        .functionResponse(
                                                FunctionResponse.builder()
                                                        .id(id)
                                                        .name("lookup")
                                                        .response(lookup(key))
                                                        .build())
                                        .build());
                        toolCalls++;
                    }
                    if (results.isEmpty()) {
                        boolean hasText =
                                content.parts().orElse(List.of()).stream()
                                        .anyMatch(
                                                part ->
                                                        part.text()
                                                                .filter(text -> !text.isBlank())
                                                                .isPresent());
                        if (!hasText || toolCalls == callsBeforeTurn) {
                            throw new IllegalStateException(
                                    "Expected tool execution and final text.");
                        }
                        completed = true;
                        break;
                    }
                    history.add(Content.builder().role("user").parts(results).build());
                }
                if (!completed)
                    throw new IllegalStateException("Tool loop exceeded three requests.");
            }
        }
        System.out.println(
                "PASS "
                        + (stream ? "stream" : "nonstream")
                        + ": two tool turns, "
                        + toolCalls
                        + " tool calls, "
                        + totalTokens
                        + " tokens");
    }
}
