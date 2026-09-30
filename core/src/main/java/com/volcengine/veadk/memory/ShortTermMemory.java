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

import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Session-scoped memory backed by ADK Java's {@link BaseSessionService}. */
public final class ShortTermMemory {

    private final Backend backend;
    private final BaseSessionService sessionService;
    private final Consumer<Session> afterCreateSessionCallback;
    private final Consumer<Session> afterLoadMemoryCallback;

    private ShortTermMemory(Builder builder) {
        this.backend = builder.backend;
        this.sessionService = Objects.requireNonNull(builder.sessionService, "sessionService");
        this.afterCreateSessionCallback = builder.afterCreateSessionCallback;
        this.afterLoadMemoryCallback = builder.afterLoadMemoryCallback;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ShortTermMemory local() {
        return builder().local().build();
    }

    public Backend backend() {
        return backend;
    }

    public BaseSessionService sessionService() {
        return sessionService;
    }

    public Maybe<Session> getSession(String appName, String userId, String sessionId) {
        return sessionService
                .getSession(
                        requireText(appName, "appName must be set."),
                        requireText(userId, "userId must be set."),
                        requireText(sessionId, "sessionId must be set."),
                        Optional.empty())
                .doOnSuccess(this::runAfterLoadMemoryCallback);
    }

    public Single<Session> createSession(String appName, String userId, String sessionId) {
        String resolvedAppName = requireText(appName, "appName must be set.");
        String resolvedUserId = requireText(userId, "userId must be set.");
        if (!hasText(sessionId)) {
            return sessionService
                    .createSession(resolvedAppName, resolvedUserId, Map.of(), null)
                    .doOnSuccess(this::runAfterCreateSessionCallback);
        }
        return getSession(resolvedAppName, resolvedUserId, sessionId)
                .switchIfEmpty(
                        Single.defer(
                                () ->
                                        sessionService
                                                .createSession(
                                                        resolvedAppName,
                                                        resolvedUserId,
                                                        Map.of(),
                                                        sessionId)
                                                .doOnSuccess(this::runAfterCreateSessionCallback)));
    }

    private void runAfterCreateSessionCallback(Session session) {
        if (afterCreateSessionCallback != null) {
            afterCreateSessionCallback.accept(session);
        }
    }

    private void runAfterLoadMemoryCallback(Session session) {
        if (afterLoadMemoryCallback != null) {
            afterLoadMemoryCallback.accept(session);
        }
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private static String requireText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    public enum Backend {
        LOCAL,
        CUSTOM
    }

    /** Builder for {@link ShortTermMemory}. */
    public static final class Builder {
        private Backend backend = Backend.LOCAL;
        private BaseSessionService sessionService = new InMemorySessionService();
        private Consumer<Session> afterCreateSessionCallback;
        private Consumer<Session> afterLoadMemoryCallback;

        public Builder local() {
            this.backend = Backend.LOCAL;
            this.sessionService = new InMemorySessionService();
            return this;
        }

        public Builder sessionService(BaseSessionService sessionService) {
            this.backend = Backend.CUSTOM;
            this.sessionService =
                    Objects.requireNonNull(sessionService, "sessionService must be set.");
            return this;
        }

        public Builder afterCreateSessionCallback(Consumer<Session> callback) {
            this.afterCreateSessionCallback =
                    Objects.requireNonNull(callback, "callback must be set.");
            return this;
        }

        public Builder afterLoadMemoryCallback(Consumer<Session> callback) {
            this.afterLoadMemoryCallback =
                    Objects.requireNonNull(callback, "callback must be set.");
            return this;
        }

        public ShortTermMemory build() {
            return new ShortTermMemory(this);
        }
    }
}
