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
package com.volcengine.veadk.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SaveSessionToMemoryCallbackTest {

    @Test
    void savesIncrementalEventsAfterThreshold() {
        SaveSessionToMemoryCallback callback =
                new SaveSessionToMemoryCallback(
                        SaveSessionToMemoryCallback.AutoSavePolicy.builder()
                                .minEventsThreshold(3)
                                .minTimeThreshold(Duration.ofDays(1))
                                .build());
        InMemorySessionService sessionService = new InMemorySessionService();
        RecordingMemoryService memoryService = new RecordingMemoryService();
        Session session = createSession(sessionService, "session-1");

        appendUserEvents(sessionService, session, "first", "second");
        callback.saveInvocation(context(sessionService, memoryService, session)).blockingAwait();

        appendUserEvents(sessionService, session, "third");
        callback.saveInvocation(context(sessionService, memoryService, session)).blockingAwait();

        appendUserEvents(sessionService, session, "fourth", "fifth");
        callback.saveInvocation(context(sessionService, memoryService, session)).blockingAwait();

        assertThat(memoryService.savedSessions()).hasSize(2);
        assertThat(texts(memoryService.savedSessions().get(0))).containsExactly("first", "second");
        assertThat(texts(memoryService.savedSessions().get(1)))
                .containsExactly("third", "fourth", "fifth");
    }

    @Test
    void sessionSwitchForceSavesPreviousSession() {
        SaveSessionToMemoryCallback callback =
                new SaveSessionToMemoryCallback(
                        SaveSessionToMemoryCallback.AutoSavePolicy.builder()
                                .minEventsThreshold(100)
                                .minTimeThreshold(Duration.ofDays(1))
                                .build());
        InMemorySessionService sessionService = new InMemorySessionService();
        RecordingMemoryService memoryService = new RecordingMemoryService();
        Session firstSession = createSession(sessionService, "session-1");
        Session secondSession = createSession(sessionService, "session-2");

        appendUserEvents(sessionService, firstSession, "first");
        callback.saveInvocation(context(sessionService, memoryService, firstSession))
                .blockingAwait();

        appendUserEvents(sessionService, firstSession, "second");
        appendUserEvents(sessionService, secondSession, "other");
        callback.saveInvocation(context(sessionService, memoryService, secondSession))
                .blockingAwait();

        assertThat(memoryService.savedSessions()).hasSize(3);
        assertThat(texts(memoryService.savedSessions().get(0))).containsExactly("first");
        assertThat(memoryService.savedSessions().get(1).id()).isEqualTo("session-1");
        assertThat(texts(memoryService.savedSessions().get(1))).containsExactly("second");
        assertThat(memoryService.savedSessions().get(2).id()).isEqualTo("session-2");
        assertThat(texts(memoryService.savedSessions().get(2))).containsExactly("other");
    }

    private static Session createSession(InMemorySessionService sessionService, String sessionId) {
        return sessionService.createSession("app", "user", null, sessionId).blockingGet();
    }

    private static void appendUserEvents(
            InMemorySessionService sessionService, Session session, String... texts) {
        for (String text : texts) {
            sessionService.appendEvent(session, textEvent(text)).blockingGet();
        }
    }

    private static Event textEvent(String text) {
        return Event.builder()
                .author("user")
                .content(Content.fromParts(Part.fromText(text)))
                .build();
    }

    private static InvocationContext context(
            InMemorySessionService sessionService,
            RecordingMemoryService memoryService,
            Session session) {
        return InvocationContext.builder()
                .agent(new TestAgent())
                .sessionService(sessionService)
                .memoryService(memoryService)
                .session(session)
                .build();
    }

    private static List<String> texts(Session session) {
        return session.events().stream()
                .map(Event::content)
                .flatMap(optional -> optional.stream())
                .flatMap(content -> content.parts().stream())
                .flatMap(List::stream)
                .map(Part::text)
                .flatMap(optional -> optional.stream())
                .toList();
    }

    private static final class RecordingMemoryService implements BaseMemoryService {
        private final List<Session> savedSessions = new ArrayList<>();

        @Override
        public Completable addSessionToMemory(Session session) {
            return Completable.fromAction(() -> savedSessions.add(session));
        }

        @Override
        public Single<SearchMemoryResponse> searchMemory(
                String appName, String userId, String query) {
            return Single.just(SearchMemoryResponse.builder().build());
        }

        private List<Session> savedSessions() {
            return savedSessions;
        }
    }

    private static final class TestAgent extends BaseAgent {
        private TestAgent() {
            super("test_agent", "test agent", List.of(), List.of(), List.of());
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
