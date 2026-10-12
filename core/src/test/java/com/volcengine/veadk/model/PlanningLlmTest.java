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

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.Runner;
import com.volcengine.veadk.planners.PlanReActPlanner;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PlanningLlmTest {
    private final PlanReActPlanner planner = new PlanReActPlanner();

    @Test
    void separatesFinalAnswerAndPreservesUsageAndToolIds() {
        Part call =
                Part.builder()
                        .functionCall(
                                FunctionCall.builder()
                                        .id("call-1")
                                        .name("lookup")
                                        .args(Map.of("key", "a"))
                                        .build())
                        .build();
        LlmResponse input =
                response(Part.fromText("/*PLANNING*/ check /*FINAL_ANSWER*/done"), call).toBuilder()
                        .usageMetadata(
                                GenerateContentResponseUsageMetadata.builder()
                                        .totalTokenCount(9)
                                        .build())
                        .customMetadata(
                                List.of(
                                        com.google.genai.types.CustomMetadata.builder()
                                                .key("provider_response_id")
                                                .stringValue("r1")
                                                .build()))
                        .build();
        LlmResponse processed = planner.processPlanningResponse(input);
        var parts = processed.content().orElseThrow().parts().orElseThrow();
        assertThat(parts).hasSize(3);
        assertThat(parts.get(0).thought()).contains(true);
        assertThat(parts.get(1).text()).contains("done");
        assertThat(parts.get(1).thought()).contains(false);
        assertThat(parts.get(2)).isEqualTo(call);
        assertThat(processed.usageMetadata()).isEqualTo(input.usageMetadata());
        assertThat(processed.customMetadata()).isEqualTo(input.customMetadata());
    }

    @Test
    void partialTagsCannotExposePlanningAsFinalAnswer() {
        for (String fragment : List.of("/*PLAN", "NING*/ check", "/*FINAL_", "ANSWER*/done")) {
            LlmResponse processed =
                    planner.processPlanningResponse(
                            response(Part.fromText(fragment)).toBuilder().partial(true).build());
            assertThat(processed.content().orElseThrow().parts().orElseThrow().get(0).thought())
                    .contains(true);
        }
        assertThat(
                        planner.processPlanningResponse(
                                        response(
                                                Part.fromText(
                                                        "/*PLANNING*/check/*FINAL_ANSWER*/done")))
                                .content()
                                .orElseThrow()
                                .parts()
                                .orElseThrow()
                                .get(1)
                                .text())
                .contains("done");
    }

    @Test
    void completeResponseParsesTagsAcrossTextPartsAndPreservesToolParts() {
        Part call =
                Part.builder()
                        .functionCall(
                                FunctionCall.builder()
                                        .id("call-1")
                                        .name("lookup")
                                        .args(Map.of("key", "a"))
                                        .build())
                        .build();
        LlmResponse processed =
                planner.processPlanningResponse(
                        response(
                                Part.fromText("/*PLANNING*/first"),
                                Part.fromText("planning continuation"),
                                Part.fromText("/*FINAL_"),
                                Part.fromText("ANSWER*/done"),
                                call));
        var parts = processed.content().orElseThrow().parts().orElseThrow();
        assertThat(parts).hasSize(4);
        assertThat(parts.get(0).thought()).contains(true);
        assertThat(parts.get(1).thought()).contains(true);
        assertThat(parts.get(2).text()).contains("done");
        assertThat(parts.get(2).thought()).contains(false);
        assertThat(parts.get(3)).isSameAs(call);
        var model =
                new FallbackLlmTest.Stub(
                        "model",
                        r ->
                                Flowable.just(
                                        response(
                                                Part.fromText("/*PLANNING*/first"),
                                                Part.fromText("planning continuation"),
                                                Part.fromText("/*FINAL_"),
                                                Part.fromText("ANSWER*/done"))));
        Agent agent =
                Agent.builder().name("multipart_planner").model(model).planner(planner).build();
        assertThat(new Runner(agent).run("hello")).isEqualTo("done");
        agent.close().blockingAwait();
    }

    @Test
    void nativeThoughtContainingFinalAnswerTagRemainsOpaque() {
        Part nativeThought =
                Part.builder().text("private /*FINAL_ANSWER*/still private").thought(true).build();
        LlmResponse input = response(nativeThought, Part.fromText("/*FINAL_ANSWER*/public answer"));
        var processed = planner.processPlanningResponse(input);
        assertThat(processed.content().orElseThrow().parts().orElseThrow().get(0))
                .isSameAs(nativeThought);
        var model = new FallbackLlmTest.Stub("model", r -> Flowable.just(input));
        Agent agent =
                Agent.builder()
                        .name("native_thought_planner")
                        .model(model)
                        .planner(planner)
                        .build();
        assertThat(new Runner(agent).run("hello")).isEqualTo("public answer");
        agent.close().blockingAwait();
    }

    @Test
    void leavesUntaggedTextAndContentlessEventsUnchanged() {
        LlmResponse plain = response(Part.fromText("hello"));
        assertThat(planner.processPlanningResponse(plain)).isEqualTo(plain);
        LlmResponse empty = LlmResponse.builder().build();
        assertThat(planner.processPlanningResponse(empty)).isSameAs(empty);
    }

    @Test
    void appendsInstructionsWithoutMutatingInputOrDuplicatingOnResubscribe() {
        var delegate =
                new FallbackLlmTest.Stub(
                        "test", request -> Flowable.just(response(Part.fromText("done"))));
        var input =
                LlmRequest.builder()
                        .model("test")
                        .contents(List.of(Content.fromParts(Part.fromText("hello"))))
                        .config(GenerateContentConfig.builder().temperature(0.1f).build())
                        .appendInstructions(List.of("original"))
                        .build();
        var output = new PlanningLlm(delegate, planner).generateContent(input, true);
        output.test().assertComplete();
        output.test().assertComplete();
        assertThat(input.getSystemInstructions()).containsExactly("original");
        assertThat(delegate.requests).hasSize(2);
        assertThat(delegate.requests.get(0).getSystemInstructions())
                .singleElement()
                .satisfies(
                        text -> assertThat(text).startsWith("original").contains("/*PLANNING*/"));
        assertThat(delegate.requests.get(0)).isEqualTo(delegate.requests.get(1));
        assertThat(delegate.requests.get(0).config().orElseThrow().temperature()).contains(0.1f);
        assertThat(delegate.stream).isTrue();
    }

    @Test
    void agentRunsFallbackPlannerAndToolLoopAndReturnsOnlyFinalAnswer() {
        AtomicInteger calls = new AtomicInteger();
        var primary =
                new FallbackLlmTest.Stub(
                        "primary", r -> Flowable.error(new IllegalStateException("offline")));
        var backup =
                new FallbackLlmTest.Stub(
                        "backup",
                        r -> {
                            assertThat(r.getSystemInstructions())
                                    .anyMatch(s -> s.contains("/*PLANNING*/"));
                            if (calls.getAndIncrement() == 0) {
                                return Flowable.just(
                                        response(
                                                Part.fromText("/*PLANNING*/Look up the key"),
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
                                            r.contents().stream()
                                                    .flatMap(
                                                            c ->
                                                                    c
                                                                            .parts()
                                                                            .orElse(List.of())
                                                                            .stream())
                                                    .flatMap(p -> p.functionResponse().stream())
                                                    .map(f -> f.id().orElse("")))
                                    .contains("lookup-1");
                            return Flowable.just(
                                    response(
                                            Part.fromText(
                                                    "/*REASONING*/Found"
                                                            + " it./*FINAL_ANSWER*/value-a")));
                        });
        Agent agent =
                Agent.builder()
                        .name("planner_agent")
                        .model(primary)
                        .modelFallbacks(backup)
                        .planner(planner)
                        .tools(FunctionTool.create(Lookup.class, "lookup"))
                        .build();
        Runner runner = new Runner(agent);
        assertThat(runner.run("user", "session", "look up a")).isEqualTo("value-a");
        var session =
                runner.sessionService()
                        .getSession("planner_agent", "user", "session", Optional.empty())
                        .blockingGet();
        assertThat(
                        session.events().stream()
                                .flatMap(e -> e.content().stream())
                                .flatMap(c -> c.parts().orElse(List.of()).stream())
                                .filter(p -> p.thought().orElse(false)))
                .isNotEmpty();
        agent.close().blockingAwait();
        assertThat(primary.closes).isEqualTo(1);
        assertThat(backup.closes).isEqualTo(1);
    }

    @Test
    void rebuildingAgentDoesNotNestWrappers() {
        var primary =
                new FallbackLlmTest.Stub(
                        "primary", r -> Flowable.error(new IllegalStateException("offline")));
        var backup =
                new FallbackLlmTest.Stub(
                        "backup",
                        r -> Flowable.just(response(Part.fromText("/*FINAL_ANSWER*/done"))));
        var builder =
                Agent.builder()
                        .name("planner_agent")
                        .model(primary)
                        .modelFallbacks(backup)
                        .planner(planner);
        builder.build();
        assertThat(new Runner(builder.build()).run("hello")).isEqualTo("done");
        assertThat(primary.requests).hasSize(1);
        assertThat(backup.requests).hasSize(1);
        assertThat(
                        backup.requests.get(0).getSystemInstructions().stream()
                                .filter(s -> s.contains("/*PLANNING*/")))
                .hasSize(1);
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
