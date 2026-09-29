/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.volcengine.veadk.runner;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.volcengine.veadk.Agent;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class Runner extends com.google.adk.runner.Runner {

    public static final String DEFAULT_USER_ID = "default_user";

    public Runner(BaseAgent agent) {
        this(agent, agent.name());
    }

    public Runner(BaseAgent agent, String appName) {
        this(agent, appName, memoryServiceFrom(agent));
    }

    public Runner(BaseAgent agent, BaseMemoryService baseMemoryService) {
        this(agent, agent.name(), baseMemoryService);
    }

    public Runner(BaseAgent agent, String appName, BaseMemoryService baseMemoryService) {
        super(
                agent,
                appName,
                new InMemoryArtifactService(),
                new InMemorySessionService(),
                resolveMemoryService(agent, baseMemoryService),
                ImmutableList.of());
    }

    public String run(String message) {
        return run(DEFAULT_USER_ID, null, message, defaultRunConfig());
    }

    public String run(String message, RunConfig runConfig) {
        return run(DEFAULT_USER_ID, null, message, runConfig);
    }

    public String run(Content content) {
        return run(DEFAULT_USER_ID, null, content, defaultRunConfig());
    }

    public String run(Content content, RunConfig runConfig) {
        return run(DEFAULT_USER_ID, null, content, runConfig);
    }

    public String run(String userId, String sessionId, String message) {
        return run(userId, sessionId, message, defaultRunConfig());
    }

    public String run(String userId, String sessionId, String message, RunConfig runConfig) {
        return run(userId, sessionId, contentFromText(message), runConfig);
    }

    public String run(String userId, String sessionId, Content content, RunConfig runConfig) {
        Objects.requireNonNull(content, "content must be set.");
        Objects.requireNonNull(runConfig, "runConfig must be set.");
        String resolvedUserId = requireText(userId, "userId must be set.");

        Session session =
                sessionService()
                        .createSession(appName(), resolvedUserId, null, sessionId)
                        .blockingGet();
        List<Event> events =
                runAsync(session.userId(), session.id(), content, runConfig).toList().blockingGet();
        return extractResponseText(events, appName(), session.userId(), session.id());
    }

    private static RunConfig defaultRunConfig() {
        return RunConfig.builder().build();
    }

    private static Content contentFromText(String message) {
        return Content.fromParts(
                Part.fromText(Objects.requireNonNull(message, "message must be set.")));
    }

    private static BaseMemoryService resolveMemoryService(
            BaseAgent agent, BaseMemoryService baseMemoryService) {
        if (baseMemoryService != null) {
            return baseMemoryService;
        }
        BaseMemoryService agentMemoryService = memoryServiceFrom(agent);
        return agentMemoryService != null ? agentMemoryService : new InMemoryMemoryService();
    }

    private static BaseMemoryService memoryServiceFrom(BaseAgent agent) {
        if (agent instanceof Agent veadkAgent) {
            return veadkAgent.longTermMemoryService().orElse(null);
        }
        return null;
    }

    private static String extractResponseText(
            List<Event> events, String appName, String userId, String sessionId) {
        String finalResponseText =
                events.stream()
                        .filter(Event::finalResponse)
                        .map(Runner::textFromEvent)
                        .filter(Runner::hasText)
                        .reduce((first, second) -> second)
                        .orElse(null);
        if (finalResponseText != null) {
            return finalResponseText;
        }

        return events.stream()
                .filter(Runner::isModelEvent)
                .map(Runner::textFromEvent)
                .filter(Runner::hasText)
                .reduce((first, second) -> second)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        String.format(
                                                "Agent run produced no text response for app '%s',"
                                                        + " user '%s', session '%s'.",
                                                appName, userId, sessionId)));
    }

    private static String textFromEvent(Event event) {
        return event.content().flatMap(Content::parts).stream()
                .flatMap(List::stream)
                .map(part -> part.text().orElse(""))
                .collect(Collectors.joining());
    }

    private static boolean isModelEvent(Event event) {
        return !"user".equals(event.author());
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
