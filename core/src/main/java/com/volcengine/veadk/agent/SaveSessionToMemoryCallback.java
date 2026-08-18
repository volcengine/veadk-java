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
package com.volcengine.veadk.agent;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Callbacks;
import com.google.adk.agents.InvocationContext;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.volcengine.veadk.utils.ReadonlyContextAccessorUtil;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SaveSessionToMemoryCallback implements Callbacks.AfterAgentCallback {

    private static final Logger log = LoggerFactory.getLogger(SaveSessionToMemoryCallback.class);

    private final SaveSessionPolicy policy;
    private final LongSupplier currentTimeMillis;
    private final BaseMemoryService memoryServiceOverride;
    private final boolean legacyFireAndForget;
    private final ConcurrentMap<SessionKey, SaveState> saveStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<UserKey, String> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UserKey, CompletableSubject> saveQueues = new ConcurrentHashMap<>();

    public SaveSessionToMemoryCallback() {
        this(SaveSessionPolicy.legacy(), System::currentTimeMillis, null, true);
    }

    public SaveSessionToMemoryCallback(SaveSessionPolicy policy) {
        this(policy, System::currentTimeMillis, null, false);
    }

    SaveSessionToMemoryCallback(SaveSessionPolicy policy, LongSupplier currentTimeMillis) {
        this(policy, currentTimeMillis, null, false);
    }

    SaveSessionToMemoryCallback(SaveSessionPolicy policy, BaseMemoryService memoryServiceOverride) {
        this(policy, System::currentTimeMillis, memoryServiceOverride, false);
    }

    private SaveSessionToMemoryCallback(
            SaveSessionPolicy policy,
            LongSupplier currentTimeMillis,
            BaseMemoryService memoryServiceOverride,
            boolean legacyFireAndForget) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
        this.memoryServiceOverride = memoryServiceOverride;
        this.legacyFireAndForget = legacyFireAndForget;
    }

    public SaveSessionPolicy policy() {
        return policy;
    }

    @Override
    public Maybe<Content> call(CallbackContext callbackContext) {
        if (legacyFireAndForget) {
            return legacyCall(callbackContext);
        }
        Completable save =
                Completable.defer(
                        () ->
                                save(
                                        ReadonlyContextAccessorUtil.getInvocationContext(
                                                callbackContext)));
        if (policy.suppressErrors()) {
            save =
                    save.onErrorComplete(
                            error -> {
                                log.error("Failed to save session", error);
                                return true;
                            });
        }
        return save.andThen(Maybe.empty());
    }

    private Maybe<Content> legacyCall(CallbackContext callbackContext) {
        InvocationContext context =
                ReadonlyContextAccessorUtil.getInvocationContext(callbackContext);
        memoryService(context)
                .addSessionToMemory(context.session())
                .subscribe(
                        () -> log.info("Saved session {}", context.session().id()),
                        error -> log.error("Failed to save session", error));
        return Maybe.empty();
    }

    private Completable save(InvocationContext context) {
        UserKey userKey = new UserKey(context.appName(), context.userId());
        return Completable.defer(
                () -> {
                    CompletableSubject completion = CompletableSubject.create();
                    CompletableSubject previous = saveQueues.put(userKey, completion);
                    Completable turn = previous == null ? Completable.complete() : previous;
                    return turn.andThen(saveOne(context, userKey))
                            .doFinally(
                                    () -> {
                                        completion.onComplete();
                                        saveQueues.remove(userKey, completion);
                                    });
                });
    }

    private Completable saveOne(InvocationContext context, UserKey userKey) {
        return Completable.defer(
                () -> {
                    Session currentSession = context.session();
                    long now = currentTimeMillis.getAsLong();
                    String previousSessionId = activeSessions.get(userKey);
                    Completable previousSave =
                            savePreviousSessionIfSwitched(
                                    context, previousSessionId, currentSession.id(), now);
                    return previousSave.andThen(
                            Completable.defer(
                                    () -> {
                                        activeSessions.put(userKey, currentSession.id());
                                        return saveCurrentSession(context, now);
                                    }));
                });
    }

    private Completable savePreviousSessionIfSwitched(
            InvocationContext context,
            String previousSessionId,
            String currentSessionId,
            long now) {
        if (!policy.saveOnSessionSwitch()
                || previousSessionId == null
                || previousSessionId.equals(currentSessionId)) {
            return Completable.complete();
        }
        BaseSessionService sessions = context.sessionService();
        BaseMemoryService memory = memoryService(context);
        return sessions.getSession(
                        context.appName(),
                        context.userId(),
                        previousSessionId,
                        java.util.Optional.empty())
                .flatMapCompletable(
                        session ->
                                memory.addSessionToMemory(session)
                                        .doOnComplete(
                                                () -> {
                                                    saveStates.put(
                                                            new SessionKey(
                                                                    context.appName(),
                                                                    context.userId(),
                                                                    previousSessionId),
                                                            new SaveState(
                                                                    now, session.events().size()));
                                                    log.info(
                                                            "Saved previous session {} after"
                                                                    + " session switch",
                                                            previousSessionId);
                                                }));
    }

    private Completable saveCurrentSession(InvocationContext context, long now) {
        String sessionId = context.session().id();
        SessionKey key = new SessionKey(context.appName(), context.userId(), sessionId);
        return context.sessionService()
                .getSession(
                        context.appName(), context.userId(), sessionId, java.util.Optional.empty())
                .switchIfEmpty(
                        Maybe.error(
                                new IllegalStateException(
                                        "Session not found in session service: " + sessionId)))
                .flatMapCompletable(
                        session -> {
                            int eventCount = session.events().size();
                            SaveState previous = saveStates.get(key);
                            if (shouldSkip(previous, eventCount, now)) {
                                return Completable.complete();
                            }
                            return memoryService(context)
                                    .addSessionToMemory(session)
                                    .doOnComplete(
                                            () -> {
                                                saveStates.put(key, new SaveState(now, eventCount));
                                                log.info("Saved session {}", sessionId);
                                            });
                        });
    }

    private BaseMemoryService memoryService(InvocationContext context) {
        return memoryServiceOverride == null ? context.memoryService() : memoryServiceOverride;
    }

    private boolean shouldSkip(SaveState previous, int currentEventCount, long now) {
        if (previous == null) {
            return false;
        }
        long elapsed = now - previous.savedAtMillis();
        int newEvents = currentEventCount - previous.eventCount();
        return elapsed < policy.minInterval().toMillis() && newEvents < policy.minNewEvents();
    }

    private record UserKey(String appName, String userId) {}

    private record SessionKey(String appName, String userId, String sessionId) {}

    private record SaveState(long savedAtMillis, int eventCount) {}
}
