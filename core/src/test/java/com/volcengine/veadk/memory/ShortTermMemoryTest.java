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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShortTermMemoryTest {

    @Test
    void defaultBuildUsesLocalInMemorySessionService() {
        ShortTermMemory memory = ShortTermMemory.builder().build();

        assertThat(memory.backend()).isEqualTo(ShortTermMemory.Backend.LOCAL);
        assertThat(memory.sessionService()).isInstanceOf(InMemorySessionService.class);
    }

    @Test
    void localBuilderUsesInMemorySessionService() {
        ShortTermMemory memory = ShortTermMemory.builder().local().build();

        assertThat(memory.backend()).isEqualTo(ShortTermMemory.Backend.LOCAL);
        assertThat(memory.sessionService()).isInstanceOf(InMemorySessionService.class);
    }

    @Test
    void customSessionServiceIsUsed() {
        BaseSessionService sessionService = mock(BaseSessionService.class);

        ShortTermMemory memory = ShortTermMemory.builder().sessionService(sessionService).build();

        assertThat(memory.backend()).isEqualTo(ShortTermMemory.Backend.CUSTOM);
        assertThat(memory.sessionService()).isSameAs(sessionService);
    }

    @Test
    void mysqlBuilderUsesPersistentSessionService() {
        ShortTermMemory memory =
                ShortTermMemory.builder()
                        .mysql("jdbc:mysql://localhost:3306/veadk", "user", "password")
                        .build();

        assertThat(memory.backend()).isEqualTo(ShortTermMemory.Backend.MYSQL);
        assertThat(memory.sessionService()).isInstanceOf(MySqlSessionService.class);
        assertThat(memory.backendOptions())
                .containsEntry("user", "user")
                .containsEntry("password", "password");
    }

    @Test
    void postgresqlBuilderUsesPersistentSessionService() {
        ShortTermMemory memory =
                ShortTermMemory.builder()
                        .postgresql("jdbc:postgresql://localhost:5432/veadk", "user", "password")
                        .build();

        assertThat(memory.backend()).isEqualTo(ShortTermMemory.Backend.POSTGRESQL);
        assertThat(memory.sessionService()).isInstanceOf(PostgresqlSessionService.class);
        assertThat(memory.backendOptions())
                .containsEntry("user", "user")
                .containsEntry("password", "password");
    }

    @Test
    void databaseUrlInfersPostgresqlBackend() {
        ShortTermMemory memory =
                ShortTermMemory.builder()
                        .databaseUrl("jdbc:postgresql://localhost:5432/veadk")
                        .backendOption("user", "user")
                        .backendOption("password", "password")
                        .build();

        assertThat(memory.backend()).isEqualTo(ShortTermMemory.Backend.POSTGRESQL);
        assertThat(memory.sessionService()).isInstanceOf(PostgresqlSessionService.class);
    }

    @Test
    void sqliteBuilderUsesPersistentSessionService(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("memory.db");
        ShortTermMemory firstMemory = ShortTermMemory.builder().sqlite(dbPath.toString()).build();

        Session session = firstMemory.createSession("app", "user", "session").blockingGet();
        firstMemory.sessionService().appendEvent(session, textEvent("hello")).blockingGet();

        ShortTermMemory secondMemory = ShortTermMemory.builder().sqlite(dbPath.toString()).build();
        Session loaded = secondMemory.getSession("app", "user", "session").blockingGet();

        assertThat(secondMemory.backend()).isEqualTo(ShortTermMemory.Backend.SQLITE);
        assertThat(secondMemory.sessionService()).isInstanceOf(SqliteSessionService.class);
        assertThat(loaded.events()).hasSize(1);
        assertThat(loaded.events().get(0).stringifyContent()).isEqualTo("hello");
    }

    @Test
    void databaseUrlInfersSqliteBackend(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("memory.db");

        ShortTermMemory memory =
                ShortTermMemory.builder().databaseUrl("jdbc:sqlite:" + dbPath).build();
        memory.createSession("app", "user", "session").blockingGet();

        assertThat(memory.backend()).isEqualTo(ShortTermMemory.Backend.SQLITE);
        assertThat(memory.sessionService()).isInstanceOf(SqliteSessionService.class);
        assertThat(dbPath).exists();
    }

    @Test
    void createSessionIsGetOrCreateAndOnlyCallsCreateCallbackForNewSession() {
        AtomicInteger createCount = new AtomicInteger();
        AtomicInteger loadCount = new AtomicInteger();
        AtomicReference<Session> created = new AtomicReference<>();
        AtomicReference<Session> loaded = new AtomicReference<>();
        ShortTermMemory memory =
                ShortTermMemory.builder()
                        .afterCreateSessionCallback(
                                session -> {
                                    createCount.incrementAndGet();
                                    created.set(session);
                                })
                        .afterLoadMemoryCallback(
                                session -> {
                                    loadCount.incrementAndGet();
                                    loaded.set(session);
                                })
                        .build();

        Session first = memory.createSession("app", "user", "session").blockingGet();
        Session second = memory.createSession("app", "user", "session").blockingGet();

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.appName()).isEqualTo(first.appName());
        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(created.get()).isSameAs(first);
        assertThat(loaded.get().id()).isEqualTo(first.id());
        assertThat(createCount).hasValue(1);
        assertThat(loadCount).hasValue(1);
        assertThat(memory.sessionService().listSessions("app", "user").blockingGet().sessions())
                .hasSize(1);
    }

    @Test
    void getSessionCallsLoadCallbackForExistingSession() {
        AtomicReference<Session> loaded = new AtomicReference<>();
        ShortTermMemory memory =
                ShortTermMemory.builder().afterLoadMemoryCallback(loaded::set).build();
        Session session = memory.createSession("app", "user", "session").blockingGet();

        Session found = memory.getSession("app", "user", "session").blockingGet();

        assertThat(found.id()).isEqualTo(session.id());
        assertThat(loaded.get().id()).isEqualTo(session.id());
    }

    @Test
    void createSessionWaitsForAsyncCallbacks() {
        List<String> calls = new ArrayList<>();
        ShortTermMemory memory =
                ShortTermMemory.builder()
                        .afterCreateSessionCallback(
                                session -> calls.add("create-sync:" + session.id()))
                        .afterCreateSessionCallbackAsync(
                                session ->
                                        Completable.fromAction(
                                                () -> calls.add("create-async:" + session.id())))
                        .afterLoadMemoryCallback(session -> calls.add("load-sync:" + session.id()))
                        .afterLoadMemoryCallbackAsync(
                                session ->
                                        Completable.fromAction(
                                                () -> calls.add("load-async:" + session.id())))
                        .build();

        memory.createSession("app", "user", "session").blockingGet();
        memory.createSession("app", "user", "session").blockingGet();

        assertThat(calls)
                .containsExactly(
                        "create-sync:session",
                        "create-async:session",
                        "load-sync:session",
                        "load-async:session");
    }

    @Test
    void asyncCallbackErrorsPropagate() {
        ShortTermMemory memory =
                ShortTermMemory.builder()
                        .afterCreateSessionCallbackAsync(
                                session -> Completable.error(new IllegalStateException("boom")))
                        .build();

        assertThatThrownBy(() -> memory.createSession("app", "user", "session").blockingGet())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");
    }

    private static Event textEvent(String text) {
        return Event.builder()
                .author("user")
                .content(Content.fromParts(Part.fromText(text)))
                .build();
    }
}
