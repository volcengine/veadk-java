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

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Callbacks;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.volcengine.veadk.utils.ReadonlyContextAccessorUtil;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SaveSessionToMemoryCallback implements Callbacks.AfterAgentCallback {

    private static final Logger log = LoggerFactory.getLogger(SaveSessionToMemoryCallback.class);
    private static final int DEFAULT_MIN_EVENTS_THRESHOLD = 10;
    private static final Duration DEFAULT_MIN_TIME_THRESHOLD = Duration.ofSeconds(60);

    private final AutoSavePolicy policy;
    private final ConcurrentMap<SessionKey, SaveState> sessionSaveCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<UserKey, String> activeSessions = new ConcurrentHashMap<>();

    public SaveSessionToMemoryCallback() {
        this(AutoSavePolicy.fromEnv());
    }

    public SaveSessionToMemoryCallback(AutoSavePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must be set.");
    }

    public AutoSavePolicy policy() {
        return policy;
    }

    @Override
    public Maybe<Content> call(CallbackContext callbackContext) {
        InvocationContext invocationContext =
                ReadonlyContextAccessorUtil.getInvocationContext(callbackContext);
        return saveInvocation(invocationContext)
                .doOnError(err -> log.error("Failed to save session", err))
                .onErrorComplete()
                .andThen(Maybe.empty());
    }

    Completable saveInvocation(InvocationContext invocationContext) {
        Objects.requireNonNull(invocationContext, "invocationContext must be set.");
        Session session = invocationContext.session();
        Instant now = Instant.now();
        UserKey userKey = new UserKey(session.appName(), session.userId());
        String previousSessionId = activeSessions.put(userKey, session.id());

        Completable savePreviousSession = Completable.complete();
        if (previousSessionId != null && !previousSessionId.equals(session.id())) {
            savePreviousSession =
                    loadSession(invocationContext, previousSessionId)
                            .flatMapCompletable(
                                    previousSession ->
                                            forceSave(
                                                    invocationContext.memoryService(),
                                                    previousSession,
                                                    now));
        }

        return savePreviousSession.andThen(
                loadSession(invocationContext, session.id())
                        .switchIfEmpty(Maybe.just(session))
                        .flatMapCompletable(
                                currentSession ->
                                        saveIfNeeded(
                                                invocationContext.memoryService(),
                                                currentSession,
                                                now)));
    }

    private Maybe<Session> loadSession(InvocationContext invocationContext, String sessionId) {
        Session session = invocationContext.session();
        return invocationContext
                .sessionService()
                .getSession(session.appName(), session.userId(), sessionId, Optional.empty());
    }

    private Completable forceSave(BaseMemoryService memoryService, Session session, Instant now) {
        return saveNewEvents(memoryService, session, now, true);
    }

    private Completable saveIfNeeded(
            BaseMemoryService memoryService, Session session, Instant now) {
        return saveNewEvents(memoryService, session, now, false);
    }

    private Completable saveNewEvents(
            BaseMemoryService memoryService, Session session, Instant now, boolean force) {
        List<Event> events = events(session);
        int currentEventCount = events.size();
        SessionKey cacheKey = new SessionKey(session.appName(), session.userId(), session.id());
        SaveState previousState = sessionSaveCache.get(cacheKey);
        int lastEventCount =
                normalizeLastEventCount(session.id(), previousState, currentEventCount);
        int newEventCount = currentEventCount - lastEventCount;

        if (newEventCount <= 0) {
            log.debug("Skipping session {} memory save: no new events.", session.id());
            return Completable.complete();
        }

        if (!force
                && previousState != null
                && policy.shouldSkip(previousState, now, newEventCount)) {
            log.debug(
                    "Skipping session {} memory save: {} new events and {} elapsed.",
                    session.id(),
                    newEventCount,
                    Duration.between(previousState.lastSaveTime(), now));
            return Completable.complete();
        }

        Session incrementalSession =
                copySessionWithEvents(session, events.subList(lastEventCount, currentEventCount));
        return Completable.defer(
                () ->
                        Completable.complete()
                                .andThen(memoryService.addSessionToMemory(incrementalSession))
                                .doOnComplete(
                                        () -> {
                                            sessionSaveCache.put(
                                                    cacheKey,
                                                    new SaveState(now, currentEventCount));
                                            log.info(
                                                    "Saved {} new events from session {} to"
                                                            + " memory.",
                                                    newEventCount,
                                                    session.id());
                                        }));
    }

    private static int normalizeLastEventCount(
            String sessionId, SaveState previousState, int currentEventCount) {
        if (previousState == null) {
            return 0;
        }
        if (previousState.lastEventCount() > currentEventCount) {
            log.warn(
                    "Saved event cursor for session {} ({}) is greater than current event count"
                            + " ({}); resetting cursor to 0.",
                    sessionId,
                    previousState.lastEventCount(),
                    currentEventCount);
            return 0;
        }
        return previousState.lastEventCount();
    }

    private static List<Event> events(Session session) {
        List<Event> events = session.events();
        return events == null ? List.of() : List.copyOf(events);
    }

    private static Session copySessionWithEvents(Session session, List<Event> events) {
        return Session.builder(session.id())
                .appName(session.appName())
                .userId(session.userId())
                .state(session.state())
                .events(events)
                .lastUpdateTime(session.lastUpdateTime())
                .build();
    }

    private record UserKey(String appName, String userId) {}

    private record SessionKey(String appName, String userId, String sessionId) {}

    private record SaveState(Instant lastSaveTime, int lastEventCount) {}

    public static final class AutoSavePolicy {

        private final int minEventsThreshold;
        private final Duration minTimeThreshold;

        private AutoSavePolicy(Builder builder) {
            if (builder.minEventsThreshold < 0) {
                throw new IllegalArgumentException("minEventsThreshold must be >= 0.");
            }
            this.minEventsThreshold = builder.minEventsThreshold;
            this.minTimeThreshold =
                    Objects.requireNonNull(
                            builder.minTimeThreshold, "minTimeThreshold must be set.");
            if (this.minTimeThreshold.isNegative()) {
                throw new IllegalArgumentException("minTimeThreshold must be >= 0.");
            }
        }

        public static Builder builder() {
            return new Builder();
        }

        public static AutoSavePolicy fromEnv() {
            return builder()
                    .minEventsThreshold(
                            readIntEnv("MIN_MESSAGES_THRESHOLD", DEFAULT_MIN_EVENTS_THRESHOLD))
                    .minTimeThreshold(
                            Duration.ofSeconds(
                                    readIntEnv(
                                            "MIN_TIME_THRESHOLD",
                                            (int) DEFAULT_MIN_TIME_THRESHOLD.toSeconds())))
                    .build();
        }

        public int minEventsThreshold() {
            return minEventsThreshold;
        }

        public Duration minTimeThreshold() {
            return minTimeThreshold;
        }

        private boolean shouldSkip(SaveState previousState, Instant now, int newEventCount) {
            Duration elapsed = Duration.between(previousState.lastSaveTime(), now);
            return elapsed.compareTo(minTimeThreshold) < 0 && newEventCount < minEventsThreshold;
        }

        private static int readIntEnv(String name, int defaultValue) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                log.warn("Invalid {} value `{}`, using default {}.", name, value, defaultValue);
                return defaultValue;
            }
        }

        public static final class Builder {
            private int minEventsThreshold = DEFAULT_MIN_EVENTS_THRESHOLD;
            private Duration minTimeThreshold = DEFAULT_MIN_TIME_THRESHOLD;

            private Builder() {}

            public Builder minEventsThreshold(int minEventsThreshold) {
                this.minEventsThreshold = minEventsThreshold;
                return this;
            }

            public Builder minTimeThreshold(Duration minTimeThreshold) {
                this.minTimeThreshold = minTimeThreshold;
                return this;
            }

            public AutoSavePolicy build() {
                return new AutoSavePolicy(this);
            }
        }
    }
}
