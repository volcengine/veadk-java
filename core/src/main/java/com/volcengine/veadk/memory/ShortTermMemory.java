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
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/** Session-scoped memory backed by ADK Java's {@link BaseSessionService}. */
public final class ShortTermMemory {

    private final Backend backend;
    private final BaseSessionService sessionService;
    private final String dbUrl;
    private final String localDatabasePath;
    private final Map<String, Object> backendOptions;
    private final Consumer<Session> afterCreateSessionCallback;
    private final Consumer<Session> afterLoadMemoryCallback;
    private final Function<Session, Completable> afterCreateSessionCallbackAsync;
    private final Function<Session, Completable> afterLoadMemoryCallbackAsync;

    private ShortTermMemory(Builder builder) {
        this.backend = builder.backend;
        this.sessionService = builder.resolveSessionService();
        this.dbUrl = Objects.requireNonNullElse(builder.dbUrl, "");
        this.localDatabasePath = Objects.requireNonNullElse(builder.localDatabasePath, "");
        this.backendOptions = Map.copyOf(builder.backendOptions);
        this.afterCreateSessionCallback = builder.afterCreateSessionCallback;
        this.afterLoadMemoryCallback = builder.afterLoadMemoryCallback;
        this.afterCreateSessionCallbackAsync = builder.afterCreateSessionCallbackAsync;
        this.afterLoadMemoryCallbackAsync = builder.afterLoadMemoryCallbackAsync;
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

    public String dbUrl() {
        return dbUrl;
    }

    public String localDatabasePath() {
        return localDatabasePath;
    }

    public Map<String, Object> backendOptions() {
        return backendOptions;
    }

    public Maybe<Session> getSession(String appName, String userId, String sessionId) {
        return sessionService
                .getSession(
                        requireText(appName, "appName must be set."),
                        requireText(userId, "userId must be set."),
                        requireText(sessionId, "sessionId must be set."),
                        Optional.empty())
                .flatMap(
                        session ->
                                runAfterLoadMemoryCallback(session).andThen(Maybe.just(session)));
    }

    public Single<Session> createSession(String appName, String userId, String sessionId) {
        String resolvedAppName = requireText(appName, "appName must be set.");
        String resolvedUserId = requireText(userId, "userId must be set.");
        if (!hasText(sessionId)) {
            return sessionService
                    .createSession(resolvedAppName, resolvedUserId, Map.of(), null)
                    .flatMap(
                            session ->
                                    runAfterCreateSessionCallback(session)
                                            .andThen(Single.just(session)));
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
                                                .flatMap(
                                                        session ->
                                                                runAfterCreateSessionCallback(
                                                                                session)
                                                                        .andThen(
                                                                                Single.just(
                                                                                        session)))));
    }

    private Completable runAfterCreateSessionCallback(Session session) {
        Completable callback = Completable.complete();
        if (afterCreateSessionCallback != null) {
            callback =
                    callback.andThen(
                            Completable.fromAction(
                                    () -> afterCreateSessionCallback.accept(session)));
        }
        return callback.andThen(invokeAsyncCallback(afterCreateSessionCallbackAsync, session));
    }

    private Completable runAfterLoadMemoryCallback(Session session) {
        Completable callback = Completable.complete();
        if (afterLoadMemoryCallback != null) {
            callback =
                    callback.andThen(
                            Completable.fromAction(() -> afterLoadMemoryCallback.accept(session)));
        }
        return callback.andThen(invokeAsyncCallback(afterLoadMemoryCallbackAsync, session));
    }

    private static Completable invokeAsyncCallback(
            Function<Session, Completable> callback, Session session) {
        if (callback == null) {
            return Completable.complete();
        }
        return Completable.defer(
                () ->
                        Objects.requireNonNull(
                                callback.apply(session), "callback must return a Completable."));
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
        SQLITE,
        MYSQL,
        POSTGRESQL,
        DATABASE,
        CUSTOM
    }

    /** Builder for {@link ShortTermMemory}. */
    public static final class Builder {
        private static final String DEFAULT_LOCAL_DATABASE_PATH = "/tmp/veadk_local_database.db";

        private Backend backend = Backend.LOCAL;
        private BaseSessionService sessionService = new InMemorySessionService();
        private String dbUrl = "";
        private String localDatabasePath = DEFAULT_LOCAL_DATABASE_PATH;
        private Map<String, Object> backendOptions = new HashMap<>();
        private Consumer<Session> afterCreateSessionCallback;
        private Consumer<Session> afterLoadMemoryCallback;
        private Function<Session, Completable> afterCreateSessionCallbackAsync;
        private Function<Session, Completable> afterLoadMemoryCallbackAsync;

        public Builder local() {
            this.backend = Backend.LOCAL;
            this.sessionService = new InMemorySessionService();
            return this;
        }

        public Builder sqlite() {
            return sqlite(DEFAULT_LOCAL_DATABASE_PATH);
        }

        public Builder sqlite(String localDatabasePath) {
            this.backend = Backend.SQLITE;
            this.localDatabasePath =
                    requireText(localDatabasePath, "localDatabasePath must be set.");
            this.dbUrl =
                    this.localDatabasePath.startsWith("jdbc:sqlite:")
                            ? this.localDatabasePath
                            : "jdbc:sqlite:" + this.localDatabasePath;
            this.sessionService = null;
            return this;
        }

        public Builder mysql(String dbUrl) {
            this.backend = Backend.MYSQL;
            this.dbUrl = requireText(dbUrl, "dbUrl must be set.");
            this.sessionService = null;
            return this;
        }

        public Builder postgresql(String dbUrl) {
            this.backend = Backend.POSTGRESQL;
            this.dbUrl = requireText(dbUrl, "dbUrl must be set.");
            this.sessionService = null;
            return this;
        }

        public Builder databaseUrl(String dbUrl) {
            this.dbUrl = requireText(dbUrl, "dbUrl must be set.");
            this.backend = inferBackend(this.dbUrl);
            this.sessionService = null;
            return this;
        }

        public Builder backendOption(String key, Object value) {
            this.backendOptions.put(requireText(key, "key must be set."), value);
            return this;
        }

        public Builder backendOptions(Map<String, Object> backendOptions) {
            this.backendOptions =
                    new HashMap<>(
                            Objects.requireNonNull(backendOptions, "backendOptions must be set."));
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

        public Builder afterCreateSessionCallbackAsync(Function<Session, Completable> callback) {
            this.afterCreateSessionCallbackAsync =
                    Objects.requireNonNull(callback, "callback must be set.");
            return this;
        }

        public Builder afterLoadMemoryCallback(Consumer<Session> callback) {
            this.afterLoadMemoryCallback =
                    Objects.requireNonNull(callback, "callback must be set.");
            return this;
        }

        public Builder afterLoadMemoryCallbackAsync(Function<Session, Completable> callback) {
            this.afterLoadMemoryCallbackAsync =
                    Objects.requireNonNull(callback, "callback must be set.");
            return this;
        }

        public ShortTermMemory build() {
            return new ShortTermMemory(this);
        }

        private BaseSessionService resolveSessionService() {
            if (backend == Backend.LOCAL) {
                return sessionService == null ? new InMemorySessionService() : sessionService;
            }
            if (backend == Backend.CUSTOM) {
                return Objects.requireNonNull(sessionService, "sessionService must be set.");
            }
            if (backend == Backend.SQLITE) {
                return new SqliteSessionService(hasText(dbUrl) ? dbUrl : localDatabasePath);
            }
            throw new UnsupportedOperationException(
                    "ShortTermMemory backend "
                            + backend
                            + " is not supported yet. Provide a custom BaseSessionService via"
                            + " sessionService(...) to use this backend.");
        }

        private static Backend inferBackend(String dbUrl) {
            String normalized = dbUrl.trim().toLowerCase();
            if (normalized.startsWith("sqlite:")
                    || normalized.startsWith("jdbc:sqlite:")
                    || normalized.endsWith(".db")) {
                return Backend.SQLITE;
            }
            if (normalized.startsWith("mysql:") || normalized.startsWith("jdbc:mysql:")) {
                return Backend.MYSQL;
            }
            if (normalized.startsWith("postgresql:")
                    || normalized.startsWith("postgres:")
                    || normalized.startsWith("jdbc:postgresql:")) {
                return Backend.POSTGRESQL;
            }
            return Backend.DATABASE;
        }
    }
}
