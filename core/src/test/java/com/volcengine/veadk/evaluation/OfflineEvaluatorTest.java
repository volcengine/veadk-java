/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.volcengine.veadk.Runner;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OfflineEvaluatorTest {
    @Test
    void evaluatesResponseAndToolTrajectoryAcrossRepeatedRuns() {
        BaseAgent agent =
                new EventAgent(
                        "eval_agent",
                        Event.builder()
                                .author("eval_agent")
                                .content(
                                        Content.fromParts(
                                                Part.fromFunctionCall(
                                                        "search",
                                                        Map.of("q", "Price", "limit", 1))))
                                .build(),
                        Event.builder()
                                .author("eval_agent")
                                .content(Content.fromParts(Part.fromText("The price is 100")))
                                .build());
        EvalInvocation invocation =
                new EvalInvocation(
                        "inv-1",
                        EvalContent.userText("Price"),
                        EvalContent.responseText("price is 100"),
                        new IntermediateData(
                                List.of(
                                        new ToolInvocation(
                                                "expected-id",
                                                Map.of("q", "Price", "limit", 1.0),
                                                "search"))));
        EvalSet evalSet =
                new EvalSet(
                        "simple",
                        "simple",
                        null,
                        List.of(new EvalCase("price", List.of(invocation), null)));

        OfflineEvaluator.EvaluationReport report =
                new OfflineEvaluator(new Runner(agent))
                        .evaluate(evalSet, new OfflineEvaluator.EvaluationOptions(2, 1.0, 0.7));

        assertThat(report.passed()).withFailMessage("%s", report).isTrue();
        assertThat(report.results()).hasSize(2).allMatch(OfflineEvaluator.CaseRunResult::passed);
        assertThat(report.results().get(0).invocations().get(0).actualTools())
                .extracting(ToolInvocation::name)
                .containsExactly("search");
    }

    @Test
    void rougeOneSupportsEnglishAndChineseTokens() {
        assertThat(OfflineEvaluator.rouge1F1("Hello world", "hello world")).isEqualTo(1.0);
        assertThat(OfflineEvaluator.rouge1F1("北京天气", "北京天气很好"))
                .isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.0001));
    }

    private static final class EventAgent extends BaseAgent {
        private final List<Event> events;

        private EventAgent(String name, Event... events) {
            super(name, "test agent", List.of(), List.of(), List.of());
            this.events = List.of(events);
        }

        @Override
        protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
            return Flowable.fromIterable(events);
        }

        @Override
        protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
            return Flowable.empty();
        }
    }
}
