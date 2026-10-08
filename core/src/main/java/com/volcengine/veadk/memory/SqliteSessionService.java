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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionException;
import com.google.adk.sessions.State;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/** SQLite-backed {@link BaseSessionService} for local persistent short-term memory. */
public final class SqliteSessionService implements BaseSessionService {

    private static final String JDBC_PREFIX = "jdbc:sqlite:";
    private static final String APP_STATE_USER_ID = "";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String jdbcUrl;

    public SqliteSessionService(String localDatabasePath) {
        this.jdbcUrl = toJdbcUrl(localDatabasePath);
        initialize();
    }

    @Override
    public Single<Session> createSession(
            String appName,
            String userId,
            @Nullable ConcurrentMap<String, Object> state,
            @Nullable String sessionId) {
        return createSession(appName, userId, (Map<String, Object>) state, sessionId);
    }

    @Override
    public Single<Session> createSession(
            String appName,
            String userId,
            @Nullable Map<String, Object> state,
            @Nullable String sessionId) {
        return Single.fromCallable(
                () -> {
                    Objects.requireNonNull(appName, "appName cannot be null");
                    Objects.requireNonNull(userId, "userId cannot be null");

                    String resolvedSessionId =
                            Optional.ofNullable(sessionId)
                                    .map(String::trim)
                                    .filter(value -> !value.isEmpty())
                                    .orElseGet(() -> UUID.randomUUID().toString());
                    ConcurrentMap<String, Object> initialState =
                            state == null
                                    ? new ConcurrentHashMap<>()
                                    : new ConcurrentHashMap<>(state);
                    Session session =
                            Session.builder(resolvedSessionId)
                                    .appName(appName)
                                    .userId(userId)
                                    .state(initialState)
                                    .lastUpdateTime(Instant.now())
                                    .build();
                    try (Connection connection = connect()) {
                        saveSession(connection, session);
                    }
                    return mergeWithGlobalState(session);
                });
    }

    @Override
    public Maybe<Session> getSession(
            String appName, String userId, String sessionId, Optional<GetSessionConfig> configOpt) {
        return Maybe.fromCallable(
                () -> {
                    Objects.requireNonNull(appName, "appName cannot be null");
                    Objects.requireNonNull(userId, "userId cannot be null");
                    Objects.requireNonNull(sessionId, "sessionId cannot be null");
                    Objects.requireNonNull(configOpt, "configOpt cannot be null");

                    try (Connection connection = connect()) {
                        Session session = readSession(connection, appName, userId, sessionId);
                        if (session == null) {
                            return null;
                        }
                        Session copy = copySession(session);
                        applyConfig(
                                copy,
                                configOpt.orElseGet(() -> GetSessionConfig.builder().build()));
                        return mergeWithGlobalState(connection, copy);
                    }
                });
    }

    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
        return Single.fromCallable(
                () -> {
                    Objects.requireNonNull(appName, "appName cannot be null");
                    Objects.requireNonNull(userId, "userId cannot be null");

                    List<Session> sessions = new ArrayList<>();
                    try (Connection connection = connect();
                            PreparedStatement statement =
                                    connection.prepareStatement(
                                            "SELECT session_json FROM veadk_sessions"
                                                    + " WHERE app_name = ? AND user_id = ?"
                                                    + " ORDER BY updated_at DESC")) {
                        statement.setString(1, appName);
                        statement.setString(2, userId);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            while (resultSet.next()) {
                                Session session = Session.fromJson(resultSet.getString(1));
                                Session copy =
                                        Session.builder(session.id())
                                                .appName(session.appName())
                                                .userId(session.userId())
                                                .lastUpdateTime(session.lastUpdateTime())
                                                .build();
                                sessions.add(mergeWithGlobalState(connection, copy));
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
                    Objects.requireNonNull(appName, "appName cannot be null");
                    Objects.requireNonNull(userId, "userId cannot be null");
                    Objects.requireNonNull(sessionId, "sessionId cannot be null");

                    try (Connection connection = connect();
                            PreparedStatement statement =
                                    connection.prepareStatement(
                                            "DELETE FROM veadk_sessions"
                                                    + " WHERE app_name = ?"
                                                    + " AND user_id = ?"
                                                    + " AND session_id = ?")) {
                        statement.setString(1, appName);
                        statement.setString(2, userId);
                        statement.setString(3, sessionId);
                        statement.executeUpdate();
                    }
                });
    }

    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
        return Single.fromCallable(
                () -> {
                    Objects.requireNonNull(appName, "appName cannot be null");
                    Objects.requireNonNull(userId, "userId cannot be null");
                    Objects.requireNonNull(sessionId, "sessionId cannot be null");

                    try (Connection connection = connect()) {
                        Session session = readSession(connection, appName, userId, sessionId);
                        if (session == null) {
                            return ListEventsResponse.builder().build();
                        }
                        return ListEventsResponse.builder()
                                .events(ImmutableList.copyOf(session.events()))
                                .build();
                    }
                });
    }

    @Override
    public Single<Event> appendEvent(Session session, Event event) {
        return Single.fromCallable(
                () -> {
                    Objects.requireNonNull(session, "session cannot be null");
                    Objects.requireNonNull(event, "event cannot be null");
                    Objects.requireNonNull(session.appName(), "session.appName cannot be null");
                    Objects.requireNonNull(session.userId(), "session.userId cannot be null");
                    Objects.requireNonNull(session.id(), "session.id cannot be null");

                    if (event.partial().orElse(false)) {
                        return event;
                    }

                    try (Connection connection = connect()) {
                        connection.setAutoCommit(false);
                        try {
                            applyStateDelta(connection, session, event);
                            BaseSessionService.super.appendEvent(session, event).blockingGet();
                            session.lastUpdateTime(Instant.ofEpochMilli(event.timestamp()));
                            saveSession(connection, session);
                            connection.commit();
                        } catch (RuntimeException | SQLException e) {
                            connection.rollback();
                            throw e;
                        } finally {
                            connection.setAutoCommit(true);
                        }
                    }
                    return event;
                });
    }

    private void initialize() {
        prepareDatabaseFile();
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS veadk_sessions ("
                            + "app_name TEXT NOT NULL,"
                            + "user_id TEXT NOT NULL,"
                            + "session_id TEXT NOT NULL,"
                            + "session_json TEXT NOT NULL,"
                            + "updated_at INTEGER NOT NULL,"
                            + "PRIMARY KEY (app_name, user_id, session_id))");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS veadk_state ("
                            + "app_name TEXT NOT NULL,"
                            + "user_id TEXT NOT NULL,"
                            + "state_key TEXT NOT NULL,"
                            + "value_json TEXT NOT NULL,"
                            + "updated_at INTEGER NOT NULL,"
                            + "PRIMARY KEY (app_name, user_id, state_key))");
        } catch (SQLException e) {
            throw new SessionException("Failed to initialize SQLite session database.", e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void prepareDatabaseFile() {
        if (!jdbcUrl.startsWith(JDBC_PREFIX)) {
            return;
        }
        String path = jdbcUrl.substring(JDBC_PREFIX.length());
        if (path.isBlank() || path.equals(":memory:") || path.startsWith("file:")) {
            return;
        }
        Path parent = Path.of(path).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (Exception e) {
            throw new SessionException("Failed to create SQLite database directory: " + parent, e);
        }
    }

    private static String toJdbcUrl(String localDatabasePathOrJdbcUrl) {
        String value = Objects.requireNonNull(localDatabasePathOrJdbcUrl, "database path is null");
        if (value.startsWith(JDBC_PREFIX)) {
            return value;
        }
        return JDBC_PREFIX + value;
    }

    private static void saveSession(Connection connection, Session session) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO veadk_sessions"
                                + " (app_name, user_id, session_id, session_json, updated_at)"
                                + " VALUES (?, ?, ?, ?, ?)"
                                + " ON CONFLICT(app_name, user_id, session_id)"
                                + " DO UPDATE SET session_json = excluded.session_json,"
                                + " updated_at = excluded.updated_at")) {
            statement.setString(1, session.appName());
            statement.setString(2, session.userId());
            statement.setString(3, session.id());
            statement.setString(4, session.toJson());
            statement.setLong(5, session.lastUpdateTime().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static @Nullable Session readSession(
            Connection connection, String appName, String userId, String sessionId)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT session_json FROM veadk_sessions"
                                + " WHERE app_name = ? AND user_id = ? AND session_id = ?")) {
            statement.setString(1, appName);
            statement.setString(2, userId);
            statement.setString(3, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return Session.fromJson(resultSet.getString(1));
            }
        }
    }

    private static Session copySession(Session original) {
        return Session.builder(original.id())
                .appName(original.appName())
                .userId(original.userId())
                .state(new ConcurrentHashMap<>(original.state()))
                .events(new ArrayList<>(original.events()))
                .lastUpdateTime(original.lastUpdateTime())
                .build();
    }

    private static void applyConfig(Session session, GetSessionConfig config) {
        List<Event> events = session.events();
        config.numRecentEvents()
                .ifPresent(
                        numRecentEvents -> {
                            if (!events.isEmpty() && numRecentEvents < events.size()) {
                                events.subList(0, events.size() - numRecentEvents).clear();
                            }
                        });
        config.afterTimestamp()
                .ifPresent(
                        afterTimestamp ->
                                events.removeIf(
                                        event ->
                                                Instant.ofEpochMilli(event.timestamp())
                                                        .isBefore(afterTimestamp)));
    }

    private Session mergeWithGlobalState(Session session) throws SQLException {
        try (Connection connection = connect()) {
            return mergeWithGlobalState(connection, session);
        }
    }

    private static Session mergeWithGlobalState(Connection connection, Session session)
            throws SQLException {
        readState(connection, session.appName(), APP_STATE_USER_ID)
                .forEach((key, value) -> session.state().put(State.APP_PREFIX + key, value));
        readState(connection, session.appName(), session.userId())
                .forEach((key, value) -> session.state().put(State.USER_PREFIX + key, value));
        return session;
    }

    private static Map<String, Object> readState(
            Connection connection, String appName, String userId) throws SQLException {
        Map<String, Object> state = new ConcurrentHashMap<>();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT state_key, value_json FROM veadk_state"
                                + " WHERE app_name = ? AND user_id = ?")) {
            statement.setString(1, appName);
            statement.setString(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    state.put(resultSet.getString(1), fromJson(resultSet.getString(2)));
                }
            }
        }
        return state;
    }

    private static void applyStateDelta(Connection connection, Session session, Event event)
            throws SQLException {
        EventActions actions = event.actions();
        if (actions == null || actions.stateDelta() == null || actions.stateDelta().isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : actions.stateDelta().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key.startsWith(State.APP_PREFIX)) {
                updateState(
                        connection,
                        session.appName(),
                        APP_STATE_USER_ID,
                        key.substring(State.APP_PREFIX.length()),
                        value);
            } else if (key.startsWith(State.USER_PREFIX)) {
                updateState(
                        connection,
                        session.appName(),
                        session.userId(),
                        key.substring(State.USER_PREFIX.length()),
                        value);
            }
        }
    }

    private static void updateState(
            Connection connection, String appName, String userId, String key, Object value)
            throws SQLException {
        if (value == State.REMOVED) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "DELETE FROM veadk_state"
                                    + " WHERE app_name = ? AND user_id = ? AND state_key = ?")) {
                statement.setString(1, appName);
                statement.setString(2, userId);
                statement.setString(3, key);
                statement.executeUpdate();
            }
            return;
        }
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO veadk_state"
                                + " (app_name, user_id, state_key, value_json, updated_at)"
                                + " VALUES (?, ?, ?, ?, ?)"
                                + " ON CONFLICT(app_name, user_id, state_key)"
                                + " DO UPDATE SET value_json = excluded.value_json,"
                                + " updated_at = excluded.updated_at")) {
            statement.setString(1, appName);
            statement.setString(2, userId);
            statement.setString(3, key);
            statement.setString(4, toJson(value));
            statement.setLong(5, Instant.now().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new SessionException("Failed to serialize session state value.", e);
        }
    }

    private static Object fromJson(String value) {
        try {
            return OBJECT_MAPPER.readValue(value, Object.class);
        } catch (JsonProcessingException e) {
            throw new SessionException("Failed to deserialize session state value.", e);
        }
    }
}
