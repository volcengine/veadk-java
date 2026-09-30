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
package com.volcengine.veadk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RunnerTest {

    @Test
    void runStringCreatesSessionWithDefaultUser() {
        EventAgent agent = new EventAgent("runner_agent", textEvent("runner_agent", "hello back"));
        Runner runner = new Runner(agent);

        String answer = runner.run("hello");

        assertThat(answer).isEqualTo("hello back");
        assertThat(agent.lastContext().userId()).isEqualTo(Runner.DEFAULT_USER_ID);
        assertThat(agent.lastContext().session().id()).isNotBlank();
        assertThat(
                        runner.sessionService()
                                .listSessions(runner.appName(), Runner.DEFAULT_USER_ID)
                                .blockingGet()
                                .sessions())
                .hasSize(1);
    }

    @Test
    void runPrefersFinalResponseText() {
        EventAgent agent =
                new EventAgent(
                        "runner_agent",
                        textEvent("runner_agent", "draft", true),
                        textEvent("runner_agent", "final"));
        Runner runner = new Runner(agent);

        assertThat(runner.run("hello")).isEqualTo("final");
    }

    @Test
    void runFallsBackToLastNonEmptyModelTextWhenThereIsNoFinalResponse() {
        EventAgent agent =
                new EventAgent(
                        "runner_agent",
                        textEvent("runner_agent", "first", true),
                        textEvent("runner_agent", "last", true));
        Runner runner = new Runner(agent);

        assertThat(runner.run("hello")).isEqualTo("last");
    }

    @Test
    void runThrowsClearErrorWhenNoTextResponseExists() {
        EventAgent agent =
                new EventAgent("runner_agent", Event.builder().author("runner_agent").build());
        Runner runner = new Runner(agent);

        assertThatThrownBy(() -> runner.run("hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no text response")
                .hasMessageContaining(Runner.DEFAULT_USER_ID);
    }

    @Test
    void runUsesExplicitUserIdAndSessionId() {
        EventAgent agent = new EventAgent("runner_agent", textEvent("runner_agent", "explicit"));
        Runner runner = new Runner(agent);

        String answer =
                runner.run(
                        "user-1",
                        "session-1",
                        "hello",
                        RunConfig.builder().autoCreateSession(true).build());

        assertThat(answer).isEqualTo("explicit");
        assertThat(agent.lastContext().userId()).isEqualTo("user-1");
        assertThat(agent.lastContext().session().id()).isEqualTo("session-1");
        assertThat(
                        runner.sessionService()
                                .getSession(
                                        runner.appName(), "user-1", "session-1", Optional.empty())
                                .blockingGet()
                                .id())
                .isEqualTo("session-1");
    }

    @Test
    void runWithExplicitMissingSessionHonorsAutoCreateSessionFalse() {
        EventAgent agent = new EventAgent("runner_agent", textEvent("runner_agent", "unused"));
        Runner runner = new Runner(agent);

        assertThatThrownBy(() -> runner.run("user-1", "missing-session", "hello"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Session not found: missing-session for user user-1");
    }

    @Test
    void runWithExplicitSessionIdReusesExistingSession() {
        EventAgent agent = new EventAgent("runner_agent", textEvent("runner_agent", "ok"));
        Runner runner = new Runner(agent);
        RunConfig autoCreateSession = RunConfig.builder().autoCreateSession(true).build();

        runner.run("user-1", "session-1", "first", autoCreateSession);
        runner.run("user-1", "session-1", "second", autoCreateSession);

        assertThat(
                        runner.sessionService()
                                .listSessions(runner.appName(), "user-1")
                                .blockingGet()
                                .sessions())
                .hasSize(1);
        assertThat(
                        runner.sessionService()
                                .listEvents(runner.appName(), "user-1", "session-1")
                                .blockingGet()
                                .events())
                .hasSize(4);
    }

    @Test
    void constructorReadsMemoryServiceFromVeadkAgent() {
        BaseMemoryService memoryService = mock(BaseMemoryService.class);
        Agent agent =
                Agent.builder()
                        .name("memory_agent")
                        .model(new TestLlm("test-model"))
                        .longTermMemory(memoryService)
                        .build();

        Runner runner = new Runner(agent);

        assertThat(runner.memoryService()).isSameAs(memoryService);
    }

    private static Event textEvent(String author, String text) {
        return textEvent(author, text, false);
    }

    private static Event textEvent(String author, String text, boolean partial) {
        return Event.builder()
                .author(author)
                .content(Content.fromParts(Part.fromText(text)))
                .partial(partial)
                .build();
    }

    private static final class EventAgent extends BaseAgent {

        private final List<Event> events;
        private InvocationContext lastContext;

        private EventAgent(String name, Event... events) {
            super(name, "test agent", List.of(), List.of(), List.of());
            this.events = List.of(events);
        }

        private InvocationContext lastContext() {
            return lastContext;
        }

        @Override
        protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
            this.lastContext = invocationContext;
            return Flowable.fromIterable(events);
        }

        @Override
        protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
            return Flowable.empty();
        }
    }

    private static final class TestLlm extends BaseLlm {

        private TestLlm(String model) {
            super(model);
        }

        @Override
        public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
            return Flowable.empty();
        }

        @Override
        public BaseLlmConnection connect(LlmRequest llmRequest) {
            throw new UnsupportedOperationException("connect is not used in this test.");
        }
    }
}
