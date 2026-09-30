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
import static org.mockito.Mockito.mock;

import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

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
}
