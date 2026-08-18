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
package com.volcengine.veadk.memory.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.events.Event;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.volcengine.veadk.memory.ShortTermMemory;
import com.volcengine.veadk.memory.ShortTermMemoryMessage;
import com.volcengine.veadk.memory.ShortTermMemoryProcessor;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteShortTermMemoryBackendTest {

    @TempDir Path directory;

    @Test
    void sessionsAndEventsPersistAcrossBackendInstances() {
        Path database = directory.resolve("nested").resolve("sessions.db");
        SQLiteShortTermMemoryBackend firstBackend = new SQLiteShortTermMemoryBackend(database);
        ShortTermMemory first = new ShortTermMemory(firstBackend);
        Session session =
                first.createSession(
                                "app",
                                "user",
                                new ConcurrentHashMap<>(Map.of("theme", "dark")),
                                "session")
                        .blockingGet();
        first.appendEvent(session, event("hello")).blockingGet();

        SQLiteShortTermMemoryBackend secondBackend = new SQLiteShortTermMemoryBackend(database);
        ShortTermMemory second = new ShortTermMemory(secondBackend);
        Session restored =
                second.getSession("app", "user", "session", Optional.empty()).blockingGet();

        assertThat(database).exists();
        assertThat(restored.state()).containsEntry("theme", "dark");
        assertThat(restored.events()).extracting(Event::stringifyContent).containsExactly("hello");
        assertThat(second.listSessions("app", "user").blockingGet().sessionIds())
                .containsExactly("session");
    }

    @Test
    void recentEventFilteringAndDeletionDoNotRewriteStoredHistory() {
        SQLiteShortTermMemoryBackend backend =
                new SQLiteShortTermMemoryBackend(directory.resolve("sessions.db"));
        ShortTermMemory memory = new ShortTermMemory(backend);
        Session session = memory.createSession("app", "user", "session").blockingGet();
        Event first = event("first");
        first.setTimestamp(Instant.now().minusSeconds(5).toEpochMilli());
        Event second = event("second");
        second.setTimestamp(Instant.now().toEpochMilli());
        memory.appendEvent(session, first).blockingGet();
        memory.appendEvent(session, second).blockingGet();
        GetSessionConfig recentOnly = GetSessionConfig.builder().numRecentEvents(1).build();

        Session filtered =
                memory.getSession("app", "user", "session", Optional.of(recentOnly)).blockingGet();

        assertThat(filtered.events()).extracting(Event::stringifyContent).containsExactly("second");
        assertThat(memory.listEvents("app", "user", "session").blockingGet().events()).hasSize(2);
        memory.deleteSession("app", "user", "session").blockingAwait();
        assertThat(
                        memory.getSession("app", "user", "session", Optional.empty())
                                .isEmpty()
                                .blockingGet())
                .isTrue();
    }

    @Test
    void staleSessionsFromDifferentBackendInstancesDoNotLoseEvents() {
        Path database = directory.resolve("shared.db");
        ShortTermMemory first = new ShortTermMemory(new SQLiteShortTermMemoryBackend(database));
        ShortTermMemory second = new ShortTermMemory(new SQLiteShortTermMemoryBackend(database));
        Session firstView = first.createSession("app", "user", "session").blockingGet();
        Session staleSecondView =
                second.getSession("app", "user", "session", Optional.empty()).blockingGet();

        first.appendEvent(firstView, event("first")).blockingGet();
        second.appendEvent(staleSecondView, event("second")).blockingGet();

        assertThat(first.listEvents("app", "user", "session").blockingGet().events())
                .extracting(Event::stringifyContent)
                .containsExactly("first", "second");
    }

    @Test
    void concurrentAppendsFromDifferentBackendInstancesAreSerialized() throws Exception {
        Path database = directory.resolve("concurrent.db");
        ShortTermMemory first = new ShortTermMemory(new SQLiteShortTermMemoryBackend(database));
        ShortTermMemory second = new ShortTermMemory(new SQLiteShortTermMemoryBackend(database));
        Session firstView = first.createSession("app", "user", "session").blockingGet();
        Session secondView =
                second.getSession("app", "user", "session", Optional.empty()).blockingGet();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstAppend =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return first.appendEvent(firstView, event("first")).blockingGet();
                            });
            Future<?> secondAppend =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return second.appendEvent(secondView, event("second"))
                                        .blockingGet();
                            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstAppend.get(5, TimeUnit.SECONDS);
            secondAppend.get(5, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(first.listEvents("app", "user", "session").blockingGet().events())
                .extracting(Event::stringifyContent)
                .containsExactlyInAnyOrder("first", "second");
    }

    @Test
    void processorViewAppendPreservesFullPersistedHistory() {
        SQLiteShortTermMemoryBackend backend =
                new SQLiteShortTermMemoryBackend(directory.resolve("processed.db"));
        ShortTermMemoryProcessor processor =
                new ShortTermMemoryProcessor(
                        messages -> List.of(new ShortTermMemoryMessage("user", "summary")));
        ShortTermMemory memory = new ShortTermMemory(backend, processor);
        Session created = memory.createSession("app", "user", "session").blockingGet();
        memory.appendEvent(created, event("first")).blockingGet();
        memory.appendEvent(created, event("second")).blockingGet();
        Session processed =
                memory.getSession("app", "user", "session", Optional.empty()).blockingGet();

        memory.appendEvent(processed, event("third")).blockingGet();

        assertThat(
                        backend.sessionService()
                                .listEvents("app", "user", "session")
                                .blockingGet()
                                .events())
                .extracting(Event::stringifyContent)
                .containsExactly("first", "second", "third");
    }

    private static Event event(String text) {
        return Event.builder()
                .author("user")
                .content(Content.builder().role("user").parts(List.of(Part.fromText(text))).build())
                .build();
    }
}
