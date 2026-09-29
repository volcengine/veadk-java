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
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.ImmutableList;
import com.volcengine.veadk.Agent;

public class Runner extends com.google.adk.runner.Runner {

    public Runner(BaseAgent agent) {
        this(agent, agent.name());
    }

    public Runner(BaseAgent agent, String appName) {
        this(agent, appName, null);
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

    private static BaseMemoryService resolveMemoryService(
            BaseAgent agent, BaseMemoryService baseMemoryService) {
        if (baseMemoryService != null) {
            return baseMemoryService;
        }
        if (agent instanceof Agent veadkAgent) {
            return veadkAgent.longTermMemoryService().orElseGet(InMemoryMemoryService::new);
        }
        return new InMemoryMemoryService();
    }
}
