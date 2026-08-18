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
package com.volcengine.veadk.agent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.volcengine.veadk.utils.ReadonlyContextAccessorUtil;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class SaveSessionToMemoryCallbackTest {

    @Test
    void legacyConstructorReturnsImmediatelyAndSavesEveryInvocation() {
        Fixture fixture = new Fixture("session");
        CompletableSubject pendingSave = CompletableSubject.create();
        when(fixture.memory.addSessionToMemory(fixture.session)).thenReturn(pendingSave);

        try (MockedStatic<ReadonlyContextAccessorUtil> accessor =
                Mockito.mockStatic(ReadonlyContextAccessorUtil.class)) {
            accessor.when(() -> ReadonlyContextAccessorUtil.getInvocationContext(fixture.callback))
                    .thenReturn(fixture.invocation);

            SaveSessionToMemoryCallback callback = new SaveSessionToMemoryCallback();
            callback.call(fixture.callback).test().assertComplete();
            callback.call(fixture.callback).test().assertComplete();

            verify(fixture.memory, times(2)).addSessionToMemory(fixture.session);
        }
    }

    @Test
    void policySkipsFrequentSmallUpdatesAndSavesAtEitherThreshold() {
        AtomicLong now = new AtomicLong(1_000L);
        SaveSessionToMemoryCallback callback =
                new SaveSessionToMemoryCallback(
                        new SaveSessionPolicy(10, Duration.ofSeconds(60), true, true), now::get);
        Fixture fixture = new Fixture("session");
        List<Event> events = new ArrayList<>();
        events.add(mock(Event.class));
        when(fixture.session.events()).thenReturn(events);

        try (MockedStatic<ReadonlyContextAccessorUtil> accessor =
                Mockito.mockStatic(ReadonlyContextAccessorUtil.class)) {
            accessor.when(() -> ReadonlyContextAccessorUtil.getInvocationContext(fixture.callback))
                    .thenReturn(fixture.invocation);

            callback.call(fixture.callback).test().assertComplete();
            callback.call(fixture.callback).test().assertComplete();
            verify(fixture.memory, times(1)).addSessionToMemory(fixture.session);

            for (int index = 0; index < 10; index++) {
                events.add(mock(Event.class));
            }
            callback.call(fixture.callback).test().assertComplete();
            verify(fixture.memory, times(2)).addSessionToMemory(fixture.session);

            now.addAndGet(Duration.ofSeconds(60).toMillis());
            callback.call(fixture.callback).test().assertComplete();
            verify(fixture.memory, times(3)).addSessionToMemory(fixture.session);
        }
    }

    @Test
    void sessionSwitchForcesPreviousSessionSaveBeforeCurrentSession() {
        SaveSessionToMemoryCallback callback =
                new SaveSessionToMemoryCallback(
                        new SaveSessionPolicy(10, Duration.ofHours(1), true, true), () -> 1_000L);
        Fixture first = new Fixture("first");
        Fixture second = new Fixture("second", first.sessions, first.memory);
        when(first.sessions.getSession("app", "user", "second", Optional.empty()))
                .thenReturn(Maybe.just(second.session));

        try (MockedStatic<ReadonlyContextAccessorUtil> accessor =
                Mockito.mockStatic(ReadonlyContextAccessorUtil.class)) {
            accessor.when(() -> ReadonlyContextAccessorUtil.getInvocationContext(first.callback))
                    .thenReturn(first.invocation);
            accessor.when(() -> ReadonlyContextAccessorUtil.getInvocationContext(second.callback))
                    .thenReturn(second.invocation);

            callback.call(first.callback).test().assertComplete();
            callback.call(second.callback).test().assertComplete();

            verify(first.memory, times(2)).addSessionToMemory(first.session);
            verify(first.memory).addSessionToMemory(second.session);
        }
    }

    @Test
    void errorHandlingCanPreserveLegacySuppressionOrPropagate() {
        Fixture fixture = new Fixture("session");
        when(fixture.memory.addSessionToMemory(fixture.session))
                .thenReturn(Completable.error(new IllegalStateException("backend unavailable")));

        try (MockedStatic<ReadonlyContextAccessorUtil> accessor =
                Mockito.mockStatic(ReadonlyContextAccessorUtil.class)) {
            accessor.when(() -> ReadonlyContextAccessorUtil.getInvocationContext(fixture.callback))
                    .thenReturn(fixture.invocation);

            new SaveSessionToMemoryCallback(new SaveSessionPolicy(0, Duration.ZERO, true, true))
                    .call(fixture.callback)
                    .test()
                    .assertComplete();
            new SaveSessionToMemoryCallback(new SaveSessionPolicy(0, Duration.ZERO, true, false))
                    .call(fixture.callback)
                    .test()
                    .assertError(IllegalStateException.class);
        }
    }

    @Test
    void concurrentSavesForSameUserAreSerialized() {
        Fixture fixture = new Fixture("session");
        CompletableSubject firstSave = CompletableSubject.create();
        when(fixture.memory.addSessionToMemory(fixture.session)).thenReturn(firstSave);
        SaveSessionToMemoryCallback callback =
                new SaveSessionToMemoryCallback(
                        new SaveSessionPolicy(10, Duration.ofMinutes(1), true, true));

        try (MockedStatic<ReadonlyContextAccessorUtil> accessor =
                Mockito.mockStatic(ReadonlyContextAccessorUtil.class)) {
            accessor.when(() -> ReadonlyContextAccessorUtil.getInvocationContext(fixture.callback))
                    .thenReturn(fixture.invocation);

            var first = callback.call(fixture.callback).test();
            var second = callback.call(fixture.callback).test();
            verify(fixture.memory, times(1)).addSessionToMemory(fixture.session);

            firstSave.onComplete();

            first.assertComplete();
            second.assertComplete();
            verify(fixture.memory, times(1)).addSessionToMemory(fixture.session);
        }
    }

    @Test
    void configuredMemoryOverridesRunnerContextMemory() {
        Fixture fixture = new Fixture("session");
        BaseMemoryService configuredMemory = mock(BaseMemoryService.class);
        when(configuredMemory.addSessionToMemory(fixture.session))
                .thenReturn(Completable.complete());
        SaveSessionToMemoryCallback callback =
                new SaveSessionToMemoryCallback(
                        new SaveSessionPolicy(0, Duration.ZERO, true, true), configuredMemory);

        try (MockedStatic<ReadonlyContextAccessorUtil> accessor =
                Mockito.mockStatic(ReadonlyContextAccessorUtil.class)) {
            accessor.when(() -> ReadonlyContextAccessorUtil.getInvocationContext(fixture.callback))
                    .thenReturn(fixture.invocation);

            callback.call(fixture.callback).test().assertComplete();

            verify(configuredMemory).addSessionToMemory(fixture.session);
            verify(fixture.memory, times(0)).addSessionToMemory(fixture.session);
        }
    }

    private static final class Fixture {
        private final CallbackContext callback = mock(CallbackContext.class);
        private final InvocationContext invocation = mock(InvocationContext.class);
        private final BaseSessionService sessions;
        private final BaseMemoryService memory;
        private final Session session = mock(Session.class);

        private Fixture(String sessionId) {
            this(sessionId, mock(BaseSessionService.class), mock(BaseMemoryService.class));
        }

        private Fixture(String sessionId, BaseSessionService sessions, BaseMemoryService memory) {
            this.sessions = sessions;
            this.memory = memory;
            when(session.id()).thenReturn(sessionId);
            when(session.events()).thenReturn(new ArrayList<>());
            when(invocation.appName()).thenReturn("app");
            when(invocation.userId()).thenReturn("user");
            when(invocation.session()).thenReturn(session);
            when(invocation.sessionService()).thenReturn(sessions);
            when(invocation.memoryService()).thenReturn(memory);
            when(sessions.getSession("app", "user", sessionId, Optional.empty()))
                    .thenReturn(Maybe.just(session));
            when(memory.addSessionToMemory(session)).thenReturn(Completable.complete());
        }
    }
}
