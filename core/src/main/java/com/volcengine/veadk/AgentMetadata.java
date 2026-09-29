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
package com.volcengine.veadk;

import java.util.List;
import java.util.Objects;

/** User-facing metadata describing an ADK agent tree. */
public record AgentMetadata(
        String id,
        String name,
        String description,
        String instructionSummary,
        String modelName,
        boolean autoSaveSession,
        boolean veadkAgent,
        List<ToolMetadata> tools,
        List<AgentMetadata> subAgents,
        List<ComponentMetadata> components,
        List<SearchSourceMetadata> searchSources) {

    public AgentMetadata {
        id = Objects.requireNonNullElse(id, "");
        name = Objects.requireNonNullElse(name, "");
        description = Objects.requireNonNullElse(description, "");
        instructionSummary = Objects.requireNonNullElse(instructionSummary, "");
        modelName = Objects.requireNonNullElse(modelName, "");
        tools = List.copyOf(Objects.requireNonNullElse(tools, List.of()));
        subAgents = List.copyOf(Objects.requireNonNullElse(subAgents, List.of()));
        components = List.copyOf(Objects.requireNonNullElse(components, List.of()));
        searchSources = List.copyOf(Objects.requireNonNullElse(searchSources, List.of()));
    }

    /** Metadata for a tool visible through explicit VeADK config or ADK public accessors. */
    public record ToolMetadata(String name, String source, String kind) {
        public ToolMetadata {
            name = Objects.requireNonNullElse(name, "");
            source = Objects.requireNonNullElse(source, "");
            kind = Objects.requireNonNullElse(kind, "");
        }
    }

    /** Metadata for a configured or reserved Agent component. */
    public record ComponentMetadata(String name, String type, boolean enabled) {
        public ComponentMetadata {
            name = Objects.requireNonNullElse(name, "");
            type = Objects.requireNonNullElse(type, "");
        }
    }

    /** Metadata for a search source available to the Agent. */
    public record SearchSourceMetadata(String name, String type, boolean enabled, String toolName) {
        public SearchSourceMetadata {
            name = Objects.requireNonNullElse(name, "");
            type = Objects.requireNonNullElse(type, "");
            toolName = Objects.requireNonNullElse(toolName, "");
        }
    }
}
