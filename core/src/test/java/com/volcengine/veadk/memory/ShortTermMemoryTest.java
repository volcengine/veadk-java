/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.volcengine.veadk.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.events.Event;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ShortTermMemoryTest {

    @Test
    void createSessionIsIdempotentAndListsSingleSession() {
        ShortTermMemory memory = new ShortTermMemory();

        Session first = memory.createSession("app", "user", "session").blockingGet();
        Session second = memory.createSession("app", "user", "session").blockingGet();

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(memory.listSessions("app", "user").blockingGet().sessions())
                .extracting(Session::id)
                .containsExactly("session");
        assertThat(memory.backend()).isEqualTo("InMemoryShortTermMemoryBackend");
    }

    @Test
    void callbackRunsAfterExistingSessionIsLoaded() {
        AtomicInteger callbackCount = new AtomicInteger();
        ShortTermMemory memory =
                new ShortTermMemory(
                        new InMemoryShortTermMemoryBackend(),
                        session -> callbackCount.incrementAndGet());
        memory.createSession("app", "user", "session").blockingGet();

        Session loaded =
                memory.getSession("app", "user", "session", Optional.empty()).blockingGet();

        assertThat(loaded).isNotNull();
        assertThat(callbackCount).hasValue(1);
    }

    @Test
    void facadeDelegatesAppendListAndDeleteOperations() {
        ShortTermMemory memory = new ShortTermMemory();
        Session session = memory.createSession("app", "user", "session").blockingGet();
        Event event =
                Event.builder()
                        .author("user")
                        .content(
                                Content.builder()
                                        .role("user")
                                        .parts(List.of(Part.fromText("hello")))
                                        .build())
                        .build();

        memory.appendEvent(session, event).blockingGet();

        assertThat(memory.listEvents("app", "user", "session").blockingGet().events())
                .extracting(Event::author)
                .containsExactly("user");
        memory.deleteSession("app", "user", "session").blockingAwait();
        assertThat(
                        memory.getSession("app", "user", "session", Optional.empty())
                                .isEmpty()
                                .blockingGet())
                .isTrue();
    }

    @Test
    void existingSessionServiceCanBeAdaptedWithoutCopyingStorage() {
        InMemorySessionService existing = new InMemorySessionService();
        ShortTermMemory memory = new ShortTermMemory(existing);

        Session created = memory.createSession("app", "user", "session").blockingGet();
        Session loaded =
                existing.getSession("app", "user", "session", Optional.empty()).blockingGet();

        assertThat(loaded.id()).isEqualTo(created.id());
        assertThat(existing.listSessions("app", "user").blockingGet().sessionIds())
                .containsExactly("session");
        assertThat(memory.sessionService()).isSameAs(memory);
    }

    @Test
    void processorRewritesLoadedHistoryWithoutMutatingStoredEvents() {
        InMemoryShortTermMemoryBackend backend = new InMemoryShortTermMemoryBackend();
        ShortTermMemoryProcessor processor =
                new ShortTermMemoryProcessor(
                        messages -> {
                            assertThat(messages)
                                    .extracting(ShortTermMemoryMessage::content)
                                    .containsExactly("first", "second");
                            return List.of(new ShortTermMemoryMessage("user", "compacted history"));
                        });
        ShortTermMemory memory = new ShortTermMemory(backend, processor);
        Session session = memory.createSession("app", "user", "session").blockingGet();
        memory.appendEvent(session, event("user", "first")).blockingGet();
        memory.appendEvent(session, event("assistant", "second")).blockingGet();

        Session optimized =
                memory.getSession("app", "user", "session", Optional.empty()).blockingGet();
        Session stored =
                backend.sessionService()
                        .getSession("app", "user", "session", Optional.empty())
                        .blockingGet();

        assertThat(optimized.events()).hasSize(1);
        assertThat(optimized.events().get(0).author()).isEqualTo("memory_optimizer");
        assertThat(optimized.events().get(0).stringifyContent()).contains("compacted history");
        assertThat(stored.events()).hasSize(2);

        memory.appendEvent(optimized, event("user", "third")).blockingGet();
        Session storedAfterAppend =
                backend.sessionService()
                        .getSession("app", "user", "session", Optional.empty())
                        .blockingGet();

        assertThat(optimized.events())
                .extracting(Event::stringifyContent)
                .containsExactly("compacted history", "third");
        assertThat(storedAfterAppend.events())
                .extracting(Event::stringifyContent)
                .containsExactly("first", "second", "third");
    }

    private static Event event(String role, String text) {
        return Event.builder()
                .author(role)
                .content(Content.builder().role(role).parts(List.of(Part.fromText(text))).build())
                .build();
    }
}
