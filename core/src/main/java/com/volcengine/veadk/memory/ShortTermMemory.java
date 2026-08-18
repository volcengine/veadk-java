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

import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/** ADK-compatible short-term-memory facade aligned with the Python VeADK contract. */
public final class ShortTermMemory implements BaseSessionService, AutoCloseable {

    private final ShortTermMemoryBackend backend;
    private final BaseSessionService delegate;
    private final ShortTermMemoryProcessor processor;
    private final Consumer<Session> afterLoadMemoryCallback;

    public ShortTermMemory() {
        this(new InMemoryShortTermMemoryBackend());
    }

    public ShortTermMemory(BaseSessionService sessionService) {
        this(new SessionServiceBackendAdapter(sessionService));
    }

    public ShortTermMemory(ShortTermMemoryBackend backend) {
        this(backend, null, session -> {});
    }

    public ShortTermMemory(
            ShortTermMemoryBackend backend, Consumer<Session> afterLoadMemoryCallback) {
        this(backend, null, afterLoadMemoryCallback);
    }

    public ShortTermMemory(ShortTermMemoryBackend backend, ShortTermMemoryProcessor processor) {
        this(backend, processor, session -> {});
    }

    public ShortTermMemory(
            ShortTermMemoryBackend backend,
            ShortTermMemoryProcessor processor,
            Consumer<Session> afterLoadMemoryCallback) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.delegate = Objects.requireNonNull(backend.sessionService(), "sessionService");
        this.processor = processor;
        this.afterLoadMemoryCallback =
                Objects.requireNonNull(afterLoadMemoryCallback, "afterLoadMemoryCallback");
    }

    public BaseSessionService sessionService() {
        return this;
    }

    public String backend() {
        return backend.backendName();
    }

    /** Creates a session, or returns the existing session when the identifier is already present. */
    public Single<Session> createSession(String appName, String userId, String sessionId) {
        return getSession(appName, userId, sessionId, Optional.empty())
                .switchIfEmpty(
                        createSession(appName, userId, new ConcurrentHashMap<>(), sessionId));
    }

    @Override
    public Single<Session> createSession(
            String appName, String userId, ConcurrentMap<String, Object> state, String sessionId) {
        return delegate.createSession(appName, userId, state, sessionId);
    }

    @Override
    public Maybe<Session> getSession(
            String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {
        return delegate.getSession(appName, userId, sessionId, config)
                .map(session -> processor == null ? session : processor.afterLoadSession(session))
                .doOnSuccess(afterLoadMemoryCallback::accept);
    }

    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
        return delegate.listSessions(appName, userId);
    }

    @Override
    public Completable deleteSession(String appName, String userId, String sessionId) {
        return delegate.deleteSession(appName, userId, sessionId);
    }

    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
        return delegate.listEvents(appName, userId, sessionId);
    }

    @Override
    public Completable closeSession(Session session) {
        return delegate.closeSession(session);
    }

    @Override
    public Single<Event> appendEvent(Session session, Event event) {
        if (processor != null) {
            // The processor returns a presentation-only session. Keep that view current for the
            // active invocation, but append to a freshly loaded canonical session so optimized or
            // filtered history never replaces the persisted history.
            return BaseSessionService.super
                    .appendEvent(session, event)
                    .flatMap(
                            appendedEvent ->
                                    delegate.getSession(
                                                    session.appName(),
                                                    session.userId(),
                                                    session.id(),
                                                    Optional.empty())
                                            .switchIfEmpty(
                                                    Single.error(
                                                            new IllegalStateException(
                                                                    "Session not found: "
                                                                            + session.id())))
                                            .flatMap(
                                                    persistedSession ->
                                                            delegate.appendEvent(
                                                                    persistedSession,
                                                                    appendedEvent)));
        }
        return delegate.appendEvent(session, event);
    }

    @Override
    public void close() {
        backend.close();
    }
}
