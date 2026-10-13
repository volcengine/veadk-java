package com.volcengine.veadk.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcSessionServiceTest {

    @Test
    void sqliteReloadsEventsAndStateFromFreshService(@TempDir Path tempDir) {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("restart.db");
        SqliteSessionService first = new SqliteSessionService(jdbcUrl);
        Session session =
                first.createSession("app", "user", Map.of("session_key", "value"), "s1")
                        .blockingGet();
        first.appendEvent(
                        session,
                        event(
                                "first",
                                Map.of(
                                        State.APP_PREFIX + "shared", "app-value",
                                        State.USER_PREFIX + "profile", "user-value")))
                .blockingGet();

        SqliteSessionService restarted = new SqliteSessionService(jdbcUrl);
        Session loaded = restarted.getSession("app", "user", "s1", Optional.empty()).blockingGet();

        assertThat(loaded.events()).extracting(Event::stringifyContent).containsExactly("first");
        assertThat(loaded.state())
                .containsEntry("session_key", "value")
                .containsEntry(State.APP_PREFIX + "shared", "app-value")
                .containsEntry(State.USER_PREFIX + "profile", "user-value");
    }

    @Test
    void sqliteKeepsAppUserAndSessionStateIsolated(@TempDir Path tempDir) {
        SqliteSessionService service =
                new SqliteSessionService("jdbc:sqlite:" + tempDir.resolve("isolation.db"));
        Session source =
                service.createSession("app-a", "user-a", Map.of("local", "source"), "s1")
                        .blockingGet();
        service.appendEvent(
                        source,
                        event(
                                "source",
                                Map.of(
                                        State.APP_PREFIX + "shared", "app-a",
                                        State.USER_PREFIX + "profile", "user-a")))
                .blockingGet();
        service.createSession("app-a", "user-b", Map.of("local", "user-b"), "s1").blockingGet();
        service.createSession("app-b", "user-a", Map.of("local", "app-b"), "s1").blockingGet();
        service.createSession("app-a", "user-a", Map.of("local", "session-2"), "s2").blockingGet();

        Session otherUser =
                service.getSession("app-a", "user-b", "s1", Optional.empty()).blockingGet();
        Session otherApp =
                service.getSession("app-b", "user-a", "s1", Optional.empty()).blockingGet();
        Session otherSession =
                service.getSession("app-a", "user-a", "s2", Optional.empty()).blockingGet();

        assertThat(otherUser.state())
                .containsEntry("local", "user-b")
                .containsEntry(State.APP_PREFIX + "shared", "app-a")
                .doesNotContainKey(State.USER_PREFIX + "profile");
        assertThat(otherApp.state())
                .containsEntry("local", "app-b")
                .doesNotContainKeys(State.APP_PREFIX + "shared", State.USER_PREFIX + "profile");
        assertThat(otherSession.state())
                .containsEntry("local", "session-2")
                .containsEntry(State.APP_PREFIX + "shared", "app-a")
                .containsEntry(State.USER_PREFIX + "profile", "user-a");
        assertThat(otherUser.events()).isEmpty();
        assertThat(otherApp.events()).isEmpty();
        assertThat(otherSession.events()).isEmpty();
    }

    @Test
    void sqliteAppliesRecentEventAndTimestampReadWindows(@TempDir Path tempDir) {
        SqliteSessionService service =
                new SqliteSessionService("jdbc:sqlite:" + tempDir.resolve("window.db"));
        Session session = service.createSession("app", "user", Map.of(), "s1").blockingGet();
        Event old = event("old", Map.of());
        old.setTimestamp(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
        Event middle = event("middle", Map.of());
        middle.setTimestamp(Instant.parse("2026-01-02T00:00:00Z").toEpochMilli());
        Event recent = event("recent", Map.of());
        recent.setTimestamp(Instant.parse("2026-01-03T00:00:00Z").toEpochMilli());
        service.appendEvent(session, old).blockingGet();
        service.appendEvent(session, middle).blockingGet();
        service.appendEvent(session, recent).blockingGet();
        GetSessionConfig config =
                GetSessionConfig.builder()
                        .numRecentEvents(2)
                        .afterTimestamp(Instant.parse("2026-01-03T00:00:00Z"))
                        .build();

        Session loaded = service.getSession("app", "user", "s1", Optional.of(config)).blockingGet();

        assertThat(loaded.events()).extracting(Event::stringifyContent).containsExactly("recent");
        assertThat(service.listEvents("app", "user", "s1").blockingGet().events())
                .extracting(Event::stringifyContent)
                .containsExactly("old", "middle", "recent");
    }

    private static Event event(String text, Map<String, Object> stateDelta) {
        return Event.builder()
                .author("user")
                .content(Content.fromParts(Part.fromText(text)))
                .actions(EventActions.builder().stateDelta(stateDelta).build())
                .build();
    }
}
