/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.volcengine.veadk.Runner;
import io.reactivex.rxjava3.core.Flowable;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalSetRecorderTest {
    @TempDir Path directory;

    @Test
    void savesCompletedSessionAndReloadsIt() throws Exception {
        BaseAgent agent = new TextAgent("record_agent", "recorded answer");
        Runner runner = new Runner(agent);
        runner.run("user-1", "session-1", "question");

        Path saved =
                new EvalSetRecorder()
                        .save(runner, "user-1", "session-1", "../../safe set", "case-1", directory);
        EvalSet loaded = new EvalSetLoader().load(saved);

        assertThat(saved.getParent()).isEqualTo(directory.toAbsolutePath());
        assertThat(saved.getFileName().toString()).isEqualTo(".._.._safe_set.evalset.json");
        assertThat(loaded.evalCases().get(0).conversation().get(0).userContent().text())
                .isEqualTo("question");
        assertThat(loaded.evalCases().get(0).conversation().get(0).finalResponse().text())
                .isEqualTo("recorded answer");

        new EvalSetRecorder()
                .save(runner, "user-1", "session-1", "../../safe set", "case-2", directory);
        assertThat(new EvalSetLoader().load(saved).evalCases())
                .extracting(EvalCase::evalId)
                .containsExactly("case-1", "case-2");
        assertThatThrownBy(
                        () ->
                                new EvalSetRecorder()
                                        .save(
                                                runner,
                                                "user-1",
                                                "session-1",
                                                "../../safe set",
                                                "case-2",
                                                directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    private static final class TextAgent extends BaseAgent {
        private final String response;

        private TextAgent(String name, String response) {
            super(name, "test agent", List.of(), List.of(), List.of());
            this.response = response;
        }

        @Override
        protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
            return Flowable.just(
                    Event.builder()
                            .author(name())
                            .content(Content.fromParts(Part.fromText(response)))
                            .build());
        }

        @Override
        protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
            return Flowable.empty();
        }
    }
}
