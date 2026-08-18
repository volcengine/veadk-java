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
package com.volcengine.veadk.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.volcengine.veadk.processors.BaseRunProcessor;
import com.volcengine.veadk.processors.RunContext;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RunnerProcessorTest {

    @Test
    void runAsync_passesImmutableContextThroughConfiguredProcessor() {
        AtomicReference<RunContext> observed = new AtomicReference<>();
        Event processedEvent = Event.builder().author("processor").build();
        BaseRunProcessor processor =
                (context, eventGenerator) -> {
                    observed.set(context);
                    return Flowable.just(processedEvent);
                };
        Runner runner = createRunner(processor);
        Session session =
                Session.builder("test-session").appName("test-app").userId("test-user").build();
        Content message = Content.builder().role("user").parts(List.of()).build();
        RunConfig runConfig = RunConfig.builder().build();

        List<Event> events =
                runner.runAsync(session, message, runConfig, Map.of("tenant", "one"))
                        .toList()
                        .blockingGet();

        assertThat(events).containsExactly(processedEvent);
        assertThat(observed.get().runner()).isSameAs(runner);
        assertThat(observed.get().message()).isSameAs(message);
        assertThat(observed.get().appName()).isEqualTo("test-app");
        assertThat(observed.get().userId()).isEqualTo("test-user");
        assertThat(observed.get().sessionId()).isEqualTo("test-session");
        assertThat(observed.get().invocationState()).containsEntry("tenant", "one");
        assertThatThrownBy(() -> observed.get().invocationState().put("other", "two"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void processorExecutionIsDeferredUntilTheEventStreamIsSubscribed() {
        AtomicReference<RunContext> observed = new AtomicReference<>();
        BaseRunProcessor processor =
                (context, eventGenerator) -> {
                    observed.set(context);
                    return Flowable.empty();
                };
        Runner runner = createRunner(processor);
        Session session =
                Session.builder("test-session").appName("test-app").userId("test-user").build();
        Content message = Content.builder().role("user").parts(List.of()).build();

        Flowable<Event> events =
                runner.runAsync(session, message, RunConfig.builder().build(), Map.of());

        assertThat(observed.get()).isNull();
        events.test().assertComplete();
        assertThat(observed.get()).isNotNull();
    }

    private static Runner createRunner(BaseRunProcessor processor) {
        return new Runner(
                new EmptyAgent(),
                "test-app",
                new InMemoryArtifactService(),
                new InMemorySessionService(),
                new InMemoryMemoryService(),
                List.of(),
                processor);
    }

    private static final class EmptyAgent extends BaseAgent {

        private EmptyAgent() {
            super("empty-agent", "", List.of(), List.of(), List.of());
        }

        @Override
        protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
            return Flowable.empty();
        }

        @Override
        protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
            return Flowable.empty();
        }
    }
}
