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

import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** SQLite implementation of the Google ADK session-service contract. */
public final class SQLiteSessionService implements BaseSessionService {

    private static final ConcurrentMap<Path, Object> DATABASE_LOCKS = new ConcurrentHashMap<>();
    private static final String BEGIN_IMMEDIATE_SQL = "BEGIN IMMEDIATE";
    private static final String COMMIT_SQL = "COMMIT";
    private static final String ROLLBACK_SQL = "ROLLBACK";

    private static final String CREATE_TABLE_SQL =
            """
            CREATE TABLE IF NOT EXISTS veadk_sessions (
                app_name TEXT NOT NULL,
                user_id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                session_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (app_name, user_id, session_id)
            )
            """;

    private final Path databasePath;
    private final String jdbcUrl;
    private final Object databaseLock;

    public SQLiteSessionService(Path databasePath) {
        this.databasePath =
                Objects.requireNonNull(databasePath, "databasePath").toAbsolutePath().normalize();
        this.databaseLock =
                DATABASE_LOCKS.computeIfAbsent(this.databasePath, unused -> new Object());
        if (this.databasePath.getParent() == null) {
            throw new IllegalArgumentException("databasePath must have a parent directory");
        }
        try {
            Files.createDirectories(this.databasePath.getParent());
            Class.forName("org.sqlite.JDBC");
        } catch (IOException | ClassNotFoundException exception) {
            throw new IllegalStateException(
                    "Unable to initialize SQLite session storage", exception);
        }
        this.jdbcUrl = "jdbc:sqlite:" + this.databasePath;
        initializeSchema();
    }

    public Path databasePath() {
        return databasePath;
    }

    @Override
    public Single<Session> createSession(
            String appName, String userId, ConcurrentMap<String, Object> state, String sessionId) {
        return Single.fromCallable(
                () -> {
                    String resolvedSessionId =
                            sessionId == null || sessionId.isBlank()
                                    ? java.util.UUID.randomUUID().toString()
                                    : sessionId;
                    Session session =
                            Session.builder(resolvedSessionId)
                                    .appName(requireText(appName, "appName"))
                                    .userId(requireText(userId, "userId"))
                                    .state(
                                            state == null
                                                    ? new ConcurrentHashMap<>()
                                                    : new ConcurrentHashMap<>(state))
                                    .lastUpdateTime(Instant.now())
                                    .build();
                    save(session);
                    return session;
                });
    }

    @Override
    public Maybe<Session> getSession(
            String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {
        return Maybe.fromCallable(
                () -> {
                    Session session = load(appName, userId, sessionId);
                    return session == null || config.isEmpty()
                            ? session
                            : applyConfig(session, config.get());
                });
    }

    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
        return Single.fromCallable(
                () -> {
                    List<Session> sessions = new ArrayList<>();
                    synchronized (databaseLock) {
                        try (Connection connection = openConnection();
                                PreparedStatement statement =
                                        connection.prepareStatement(
                                                "SELECT session_json FROM veadk_sessions "
                                                        + "WHERE app_name = ? AND user_id = ? "
                                                        + "ORDER BY updated_at, session_id")) {
                            statement.setString(1, requireText(appName, "appName"));
                            statement.setString(2, requireText(userId, "userId"));
                            try (ResultSet results = statement.executeQuery()) {
                                while (results.next()) {
                                    sessions.add(Session.fromJson(results.getString(1)));
                                }
                            }
                        }
                    }
                    return ListSessionsResponse.builder().sessions(sessions).build();
                });
    }

    @Override
    public Completable deleteSession(String appName, String userId, String sessionId) {
        return Completable.fromAction(
                () -> {
                    synchronized (databaseLock) {
                        try (Connection connection = openConnection();
                                PreparedStatement statement =
                                        connection.prepareStatement(
                                                "DELETE FROM veadk_sessions WHERE app_name = ? "
                                                        + "AND user_id = ? AND session_id = ?")) {
                            statement.setString(1, requireText(appName, "appName"));
                            statement.setString(2, requireText(userId, "userId"));
                            statement.setString(3, requireText(sessionId, "sessionId"));
                            statement.executeUpdate();
                        }
                    }
                });
    }

    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
        return Single.fromCallable(
                () -> {
                    Session session = load(appName, userId, sessionId);
                    List<Event> events = session == null ? List.of() : session.events();
                    return ListEventsResponse.builder().events(events).build();
                });
    }

    @Override
    public Single<Event> appendEvent(Session session, Event event) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(event, "event");
        return Single.fromCallable(
                () -> {
                    synchronized (databaseLock) {
                        try (Connection connection = openConnection()) {
                            execute(connection, BEGIN_IMMEDIATE_SQL);
                            try {
                                Session persisted =
                                        load(
                                                connection,
                                                session.appName(),
                                                session.userId(),
                                                session.id());
                                if (persisted == null) {
                                    throw new IllegalStateException(
                                            "Session not found: " + session.id());
                                }
                                Event appendedEvent =
                                        BaseSessionService.super
                                                .appendEvent(persisted, event)
                                                .blockingGet();
                                BaseSessionService.super.appendEvent(session, event).blockingGet();
                                save(connection, persisted);
                                execute(connection, COMMIT_SQL);
                                return appendedEvent;
                            } catch (Exception exception) {
                                rollback(connection, exception);
                                throw exception;
                            }
                        }
                    }
                });
    }

    private void initializeSchema() {
        synchronized (databaseLock) {
            try (Connection connection = openConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(CREATE_TABLE_SQL);
            } catch (SQLException exception) {
                throw new IllegalStateException(
                        "Unable to create SQLite session schema", exception);
            }
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try {
            execute(connection, "PRAGMA busy_timeout = 5000");
            return connection;
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
    }

    private Session load(String appName, String userId, String sessionId) throws SQLException {
        synchronized (databaseLock) {
            try (Connection connection = openConnection()) {
                return load(connection, appName, userId, sessionId);
            }
        }
    }

    private Session load(Connection connection, String appName, String userId, String sessionId)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT session_json FROM veadk_sessions WHERE app_name = ? "
                                + "AND user_id = ? AND session_id = ?")) {
            statement.setString(1, requireText(appName, "appName"));
            statement.setString(2, requireText(userId, "userId"));
            statement.setString(3, requireText(sessionId, "sessionId"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Session.fromJson(result.getString(1)) : null;
            }
        }
    }

    private void save(Session session) throws SQLException {
        synchronized (databaseLock) {
            try (Connection connection = openConnection()) {
                save(connection, session);
            }
        }
    }

    private void save(Connection connection, Session session) throws SQLException {
        session.lastUpdateTime(Instant.now());
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT OR REPLACE INTO veadk_sessions (app_name, user_id, session_id,"
                                + " session_json, updated_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, session.appName());
            statement.setString(2, session.userId());
            statement.setString(3, session.id());
            statement.setString(4, session.toJson());
            statement.setLong(5, session.lastUpdateTime().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void rollback(Connection connection, Exception originalException) {
        try {
            execute(connection, ROLLBACK_SQL);
        } catch (SQLException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }

    private static Session applyConfig(Session session, GetSessionConfig config) {
        List<Event> events = new ArrayList<>(session.events());
        config.afterTimestamp()
                .ifPresent(
                        timestamp ->
                                events.removeIf(
                                        event -> event.timestamp() <= timestamp.toEpochMilli()));
        config.numRecentEvents()
                .ifPresent(
                        limit -> {
                            if (limit < 0) {
                                throw new IllegalArgumentException(
                                        "numRecentEvents must not be negative");
                            }
                            if (events.size() > limit) {
                                events.subList(0, events.size() - limit).clear();
                            }
                        });
        return Session.builder(session.id())
                .appName(session.appName())
                .userId(session.userId())
                .state(new ConcurrentHashMap<>(session.state()))
                .events(events)
                .lastUpdateTime(session.lastUpdateTime())
                .build();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
