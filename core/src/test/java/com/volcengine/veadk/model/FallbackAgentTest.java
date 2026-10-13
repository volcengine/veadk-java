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
package com.volcengine.veadk.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.models.LlmResponse;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.Runner;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FallbackAgentTest {
    @Test
    void agentRunsFallbackAndToolLoopWithoutChangingInstructionsOrAnswer() {
        AtomicInteger calls = new AtomicInteger();
        var primary =
                new FallbackLlmTest.Stub(
                        "primary", r -> Flowable.error(new IllegalStateException("offline")));
        var backup =
                new FallbackLlmTest.Stub(
                        "backup",
                        request -> {
                            assertThat(request.getSystemInstructions())
                                    .isEqualTo(
                                            primary.requests
                                                    .get(primary.requests.size() - 1)
                                                    .getSystemInstructions());
                            assertThat(request.tools()).containsKey("lookup");
                            if (calls.getAndIncrement() == 0) {
                                return Flowable.just(
                                        response(
                                                Part.builder()
                                                        .functionCall(
                                                                FunctionCall.builder()
                                                                        .id("lookup-1")
                                                                        .name("lookup")
                                                                        .args(Map.of("key", "a"))
                                                                        .build())
                                                        .build()));
                            }
                            assertThat(
                                            request.contents().stream()
                                                    .flatMap(
                                                            content ->
                                                                    content
                                                                            .parts()
                                                                            .orElse(List.of())
                                                                            .stream())
                                                    .flatMap(
                                                            part ->
                                                                    part
                                                                            .functionResponse()
                                                                            .stream()))
                                    .anySatisfy(
                                            result -> {
                                                assertThat(result.id()).contains("lookup-1");
                                                assertThat(result.response().orElseThrow())
                                                        .containsEntry("value", "value-a");
                                            });
                            return Flowable.just(response(Part.fromText("value-a")));
                        });
        Agent agent =
                Agent.builder()
                        .name("fallback_agent")
                        .instruction("Use the lookup tool.")
                        .model(primary)
                        .modelFallbacks(backup)
                        .tools(FunctionTool.create(Lookup.class, "lookup"))
                        .build();
        try {
            Runner runner = new Runner(agent);
            assertThat(runner.run("user", "session", "look up a")).isEqualTo("value-a");
            var session =
                    runner.sessionService()
                            .getSession("fallback_agent", "user", "session", Optional.empty())
                            .blockingGet();
            assertThat(
                            session.events().stream()
                                    .flatMap(event -> event.content().stream())
                                    .flatMap(content -> content.parts().orElse(List.of()).stream())
                                    .flatMap(part -> part.functionResponse().stream()))
                    .anySatisfy(result -> assertThat(result.id()).contains("lookup-1"));
            assertThat(primary.requests).hasSize(2);
            assertThat(backup.requests).hasSize(2);
        } finally {
            agent.close().blockingAwait();
        }
        assertThat(primary.closes).isEqualTo(1);
        assertThat(backup.closes).isEqualTo(1);
    }

    @Test
    void rebuildingAgentDoesNotNestFallbackWrappers() {
        var primary =
                new FallbackLlmTest.Stub(
                        "primary", r -> Flowable.error(new IllegalStateException("offline")));
        var backup =
                new FallbackLlmTest.Stub(
                        "backup", r -> Flowable.just(response(Part.fromText("done"))));
        var builder = Agent.builder().name("fallback_agent").model(primary).modelFallbacks(backup);
        builder.build();
        var agent = builder.build();
        try {
            assertThat(new Runner(agent).run("hello")).isEqualTo("done");
            assertThat(primary.requests).hasSize(1);
            assertThat(backup.requests).hasSize(1);
        } finally {
            agent.close().blockingAwait();
        }
        assertThat(primary.closes).isEqualTo(1);
        assertThat(backup.closes).isEqualTo(1);
    }

    public static class Lookup {
        public static Map<String, Object> lookup(@Schema(name = "key") String key) {
            return Map.of("value", "value-" + key);
        }
    }

    private static LlmResponse response(Part... parts) {
        return LlmResponse.builder()
                .content(Content.builder().role("model").parts(parts).build())
                .build();
    }
}
