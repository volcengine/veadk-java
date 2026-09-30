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

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Instruction;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.Model;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Extracts Agent metadata from explicit VeADK public contracts and ADK public accessors. */
public final class AgentMetadataExtractor {

    public static final String TOOL_SOURCE_EXPLICIT = "explicit";
    public static final String TOOL_SOURCE_AUTO = "auto";
    public static final String TOOL_SOURCE_ADK = "adk";

    public static final String WEB_SEARCH_TOOL_NAME = "web_search";
    public static final String KNOWLEDGEBASE_TOOL_NAME = "loadKnowledgebase";
    public static final String MEMORY_TOOL_NAME = "loadMemory";

    private AgentMetadataExtractor() {}

    public static AgentMetadata extract(BaseAgent agent) {
        Objects.requireNonNull(agent, "agent must be set.");
        return extract(agent, null, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static AgentMetadata extract(
            BaseAgent agent, String parentId, Set<BaseAgent> currentPath) {
        String name = Objects.requireNonNullElse(agent.name(), "");
        String id = parentId == null || parentId.isBlank() ? name : parentId + "/" + name;
        if (!currentPath.add(agent)) {
            return new AgentMetadata(
                    id,
                    name,
                    agent.description(),
                    "",
                    "",
                    false,
                    agent instanceof Agent,
                    List.of(),
                    List.of(),
                    reservedComponents(false, false, false),
                    searchSources(false, false, false));
        }

        AgentMetadata metadata;
        if (agent instanceof Agent veadkAgent) {
            metadata = extractVeadkAgent(veadkAgent, id, currentPath);
        } else {
            metadata = extractAdkAgent(agent, id, currentPath);
        }
        currentPath.remove(agent);
        return metadata;
    }

    private static AgentMetadata extractVeadkAgent(
            Agent agent, String id, Set<BaseAgent> currentPath) {
        AgentMetadataSnapshot snapshot = agent.metadataSnapshot();
        List<AgentMetadata.ToolMetadata> tools = toolsFromSnapshot(snapshot);
        boolean hasWebSearch = hasTool(tools, WEB_SEARCH_TOOL_NAME);
        boolean hasToolset = !agent.toolsets().isEmpty();

        return new AgentMetadata(
                id,
                snapshot.name(),
                snapshot.description(),
                snapshot.instructionSummary(),
                snapshot.modelName(),
                snapshot.autoSaveSession(),
                true,
                tools,
                subAgents(agent, id, currentPath),
                components(snapshot.hasKnowledgebase(), snapshot.hasLongTermMemory(), hasToolset),
                searchSources(
                        snapshot.hasKnowledgebase(), snapshot.hasLongTermMemory(), hasWebSearch));
    }

    private static AgentMetadata extractAdkAgent(
            BaseAgent agent, String id, Set<BaseAgent> currentPath) {
        List<AgentMetadata.ToolMetadata> tools = List.of();
        String instructionSummary = "";
        String modelName = "";
        boolean hasToolset = false;
        if (agent instanceof LlmAgent llmAgent) {
            tools = toolsFromLlmAgent(llmAgent);
            instructionSummary = instructionSummary(llmAgent.instruction());
            modelName = modelName(llmAgent);
            hasToolset = !llmAgent.toolsets().isEmpty();
        }

        boolean hasKnowledgebase = hasTool(tools, KNOWLEDGEBASE_TOOL_NAME);
        boolean hasLongTermMemory = hasTool(tools, MEMORY_TOOL_NAME);
        boolean hasWebSearch = hasTool(tools, WEB_SEARCH_TOOL_NAME);
        return new AgentMetadata(
                id,
                agent.name(),
                agent.description(),
                instructionSummary,
                modelName,
                false,
                false,
                tools,
                subAgents(agent, id, currentPath),
                components(hasKnowledgebase, hasLongTermMemory, hasToolset),
                searchSources(hasKnowledgebase, hasLongTermMemory, hasWebSearch));
    }

    private static List<AgentMetadata.ToolMetadata> toolsFromSnapshot(
            AgentMetadataSnapshot snapshot) {
        Map<String, AgentMetadata.ToolMetadata> tools = new LinkedHashMap<>();
        snapshot.explicitToolNames().stream()
                .map(name -> new AgentMetadata.ToolMetadata(name, TOOL_SOURCE_EXPLICIT, "tool"))
                .forEach(tool -> tools.putIfAbsent(tool.name(), tool));
        snapshot.autoToolNames().stream()
                .map(name -> new AgentMetadata.ToolMetadata(name, TOOL_SOURCE_AUTO, "tool"))
                .forEach(tool -> tools.putIfAbsent(tool.name(), tool));
        return List.copyOf(tools.values());
    }

    private static List<AgentMetadata.ToolMetadata> toolsFromLlmAgent(LlmAgent agent) {
        return agent.toolsUnion().stream()
                .map(tool -> toolMetadata(tool, TOOL_SOURCE_ADK))
                .toList();
    }

    private static AgentMetadata.ToolMetadata toolMetadata(Object tool, String source) {
        Objects.requireNonNull(tool, "tool must not be null.");
        if (tool instanceof BaseTool baseTool) {
            return new AgentMetadata.ToolMetadata(baseTool.name(), source, "tool");
        }
        if (tool instanceof BaseToolset baseToolset) {
            return new AgentMetadata.ToolMetadata(
                    baseToolset.getClass().getSimpleName(), source, "toolset");
        }
        return new AgentMetadata.ToolMetadata(tool.getClass().getSimpleName(), source, "unknown");
    }

    private static List<AgentMetadata> subAgents(
            BaseAgent agent, String id, Set<BaseAgent> currentPath) {
        List<AgentMetadata> subAgents = new ArrayList<>();
        for (BaseAgent subAgent : agent.subAgents()) {
            subAgents.add(extract(subAgent, id, currentPath));
        }
        return List.copyOf(subAgents);
    }

    private static List<AgentMetadata.ComponentMetadata> components(
            boolean hasKnowledgebase, boolean hasLongTermMemory, boolean hasToolset) {
        return List.of(
                new AgentMetadata.ComponentMetadata(
                        "knowledgebase", "knowledgebase", hasKnowledgebase),
                new AgentMetadata.ComponentMetadata("longTermMemory", "memory", hasLongTermMemory),
                new AgentMetadata.ComponentMetadata("shortTermMemory", "session", false),
                new AgentMetadata.ComponentMetadata("tracer", "observability", false),
                new AgentMetadata.ComponentMetadata("toolset", "tools", hasToolset),
                new AgentMetadata.ComponentMetadata("plugin", "runtime", false));
    }

    private static List<AgentMetadata.ComponentMetadata> reservedComponents(
            boolean hasKnowledgebase, boolean hasLongTermMemory, boolean hasToolset) {
        return components(hasKnowledgebase, hasLongTermMemory, hasToolset);
    }

    private static List<AgentMetadata.SearchSourceMetadata> searchSources(
            boolean hasKnowledgebase, boolean hasLongTermMemory, boolean hasWebSearch) {
        return List.of(
                new AgentMetadata.SearchSourceMetadata(
                        "web", "web_search", hasWebSearch, WEB_SEARCH_TOOL_NAME),
                new AgentMetadata.SearchSourceMetadata(
                        "knowledge", "knowledgebase", hasKnowledgebase, KNOWLEDGEBASE_TOOL_NAME),
                new AgentMetadata.SearchSourceMetadata(
                        "memory", "long_term_memory", hasLongTermMemory, MEMORY_TOOL_NAME));
    }

    private static boolean hasTool(List<AgentMetadata.ToolMetadata> tools, String name) {
        return tools.stream().anyMatch(tool -> name.equals(tool.name()));
    }

    private static String instructionSummary(Instruction instruction) {
        if (instruction instanceof Instruction.Static staticInstruction) {
            return staticInstruction.instruction();
        }
        if (instruction == null) {
            return "";
        }
        return instruction.getClass().getSimpleName();
    }

    private static String modelName(LlmAgent agent) {
        return agent.model()
                .flatMap(Model::modelName)
                .or(() -> agent.model().flatMap(Model::model).map(BaseLlm::model))
                .orElse("");
    }
}
