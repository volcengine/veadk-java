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
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.volcengine.veadk.agent.Agent;
import com.volcengine.veadk.processors.BaseRunProcessor;
import com.volcengine.veadk.processors.NoOpRunProcessor;
import com.volcengine.veadk.processors.RunContext;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Runner extends com.google.adk.runner.Runner {

    private final BaseRunProcessor runProcessor;

    public Runner(BaseAgent agent) {
        this(agent, agent.name());
    }

    public Runner(BaseAgent agent, String appName) {
        this(agent, appName, (BaseMemoryService) null);
    }

    public Runner(BaseAgent agent, BaseMemoryService baseMemoryService) {
        this(agent, agent.name(), baseMemoryService);
    }

    public Runner(BaseAgent agent, String appName, BaseMemoryService baseMemoryService) {
        this(
                agent,
                appName,
                new InMemoryArtifactService(),
                defaultSessionService(agent),
                null != baseMemoryService ? baseMemoryService : defaultMemoryService(agent),
                defaultPlugins(agent),
                defaultRunProcessor(agent));
    }

    public static Runner withProcessor(BaseAgent agent, BaseRunProcessor runProcessor) {
        return withProcessor(agent, agent.name(), runProcessor);
    }

    public static Runner withProcessor(
            BaseAgent agent, String appName, BaseRunProcessor runProcessor) {
        return new Runner(
                agent,
                appName,
                new InMemoryArtifactService(),
                defaultSessionService(agent),
                defaultMemoryService(agent),
                defaultPlugins(agent),
                runProcessor);
    }

    public Runner(
            BaseAgent agent,
            String appName,
            BaseArtifactService artifactService,
            BaseSessionService sessionService,
            BaseMemoryService memoryService,
            List<BasePlugin> plugins) {
        this(
                agent,
                appName,
                artifactService,
                sessionService,
                memoryService,
                plugins,
                NoOpRunProcessor.INSTANCE);
    }

    public Runner(
            BaseAgent agent,
            String appName,
            BaseArtifactService artifactService,
            BaseSessionService sessionService,
            BaseMemoryService memoryService,
            List<BasePlugin> plugins,
            BaseRunProcessor runProcessor) {
        super(
                agent,
                appName,
                Objects.requireNonNull(artifactService, "artifactService"),
                Objects.requireNonNull(sessionService, "sessionService"),
                Objects.requireNonNull(memoryService, "memoryService"),
                List.copyOf(Objects.requireNonNull(plugins, "plugins")));
        this.runProcessor = Objects.requireNonNull(runProcessor, "runProcessor");
    }

    public BaseRunProcessor runProcessor() {
        return runProcessor;
    }

    private static BaseSessionService defaultSessionService(BaseAgent agent) {
        return agent instanceof Agent veadkAgent && veadkAgent.shortTermMemory() != null
                ? veadkAgent.shortTermMemory()
                : new InMemorySessionService();
    }

    private static BaseMemoryService defaultMemoryService(BaseAgent agent) {
        return agent instanceof Agent veadkAgent && veadkAgent.longTermMemory() != null
                ? veadkAgent.longTermMemory()
                : new InMemoryMemoryService();
    }

    private static List<BasePlugin> defaultPlugins(BaseAgent agent) {
        return agent instanceof Agent veadkAgent ? veadkAgent.plugins() : List.of();
    }

    private static BaseRunProcessor defaultRunProcessor(BaseAgent agent) {
        return agent instanceof Agent veadkAgent
                ? veadkAgent.runProcessor()
                : NoOpRunProcessor.INSTANCE;
    }

    @Override
    public Flowable<Event> runAsync(
            Session session,
            Content newMessage,
            RunConfig runConfig,
            Map<String, Object> stateDelta) {
        RunContext context = new RunContext(this, session, newMessage, runConfig, stateDelta);
        return Flowable.defer(
                () ->
                        runProcessor.processRun(
                                context,
                                () -> super.runAsync(session, newMessage, runConfig, stateDelta)));
    }
}
