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

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stable, JSON-safe metadata and recursive topology extracted from an ADK agent. */
public record AgentMetadata(
        String name,
        String description,
        String agentClass,
        String model,
        List<String> searchSources,
        List<ToolSummary> tools,
        List<AgentComponent> components,
        List<SkillSummary> skills,
        List<AgentMetadata> subAgents) {

    private static final Set<String> WEB_SEARCH_TOOL_NAMES =
            Set.of("parallel_web_search", "vesearch", "web_search");

    public AgentMetadata {
        name = Objects.requireNonNullElse(name, "");
        description = Objects.requireNonNullElse(description, "");
        agentClass = Objects.requireNonNullElse(agentClass, "");
        model = Objects.requireNonNullElse(model, "");
        searchSources = List.copyOf(searchSources);
        tools = List.copyOf(tools);
        components = List.copyOf(components);
        skills = List.copyOf(skills);
        subAgents = List.copyOf(subAgents);
    }

    public static AgentMetadata from(BaseAgent agent) {
        Objects.requireNonNull(agent, "agent");
        return extract(agent, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static AgentMetadata extract(BaseAgent agent, Set<BaseAgent> path) {
        if (!path.add(agent)) {
            return new AgentMetadata(
                    agent.name(),
                    agent.description(),
                    agent.getClass().getSimpleName(),
                    "",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        }

        List<ToolSummary> tools = tools(agent);
        List<AgentComponent> components = new ArrayList<>();
        List<SkillSummary> skills = new ArrayList<>();
        Set<String> searchSources = new LinkedHashSet<>();
        if (tools.stream().map(ToolSummary::name).anyMatch(WEB_SEARCH_TOOL_NAMES::contains)) {
            searchSources.add("web");
        }
        if (agent instanceof AgentComponentProvider provider) {
            components.addAll(deduplicateComponents(provider.agentComponents()));
            skills.addAll(deduplicateSkills(provider.agentSkills()));
            searchSources.addAll(provider.additionalSearchSources());
            components.stream()
                    .map(AgentMetadata::componentSearchSource)
                    .filter(source -> !source.isEmpty())
                    .forEach(searchSources::add);
        }

        List<AgentMetadata> children =
                agent.subAgents().stream().map(child -> extract(child, path)).toList();
        path.remove(agent);
        return new AgentMetadata(
                agent.name(),
                agent.description(),
                agent.getClass().getSimpleName(),
                modelName(agent),
                List.copyOf(searchSources),
                tools,
                components,
                skills,
                children);
    }

    private static List<ToolSummary> tools(BaseAgent agent) {
        if (!(agent instanceof LlmAgent llmAgent)) {
            return List.of();
        }
        Map<String, ToolSummary> summaries = new LinkedHashMap<>();
        for (BaseTool tool : llmAgent.tools()) {
            summaries.putIfAbsent(
                    tool.name(),
                    new ToolSummary(
                            tool.name(), tool.description(), tool.getClass().getSimpleName()));
        }
        for (BaseToolset toolset : llmAgent.toolsets()) {
            String name = toolset.getClass().getSimpleName();
            summaries.putIfAbsent(name, new ToolSummary(name, "", "toolset"));
        }
        return List.copyOf(summaries.values());
    }

    private static String modelName(BaseAgent agent) {
        if (!(agent instanceof LlmAgent llmAgent) || llmAgent.model().isEmpty()) {
            return "";
        }
        com.google.adk.models.Model model = llmAgent.model().get();
        return model.modelName()
                .orElseGet(() -> model.model().map(modelLlm -> modelLlm.model()).orElse(""));
    }

    private static List<AgentComponent> deduplicateComponents(List<AgentComponent> components) {
        Map<String, AgentComponent> unique = new LinkedHashMap<>();
        for (AgentComponent component : components) {
            unique.putIfAbsent(component.kind() + "\u0000" + component.name(), component);
        }
        return List.copyOf(unique.values());
    }

    private static List<SkillSummary> deduplicateSkills(List<SkillSummary> skills) {
        Map<String, SkillSummary> unique = new LinkedHashMap<>();
        for (SkillSummary skill : skills) {
            unique.putIfAbsent(skill.name(), skill);
        }
        return List.copyOf(unique.values());
    }

    private static String componentSearchSource(AgentComponent component) {
        String kind = component.kind().toLowerCase(Locale.ROOT);
        if (kind.equals("knowledgebase") || kind.equals("knowledge")) {
            return "knowledge";
        }
        if (kind.equals("long_term_memory") || kind.equals("memory")) {
            return "memory";
        }
        return "";
    }

    public record ToolSummary(String name, String description, String type) {
        public ToolSummary {
            name = Objects.requireNonNullElse(name, "");
            description = Objects.requireNonNullElse(description, "");
            type = Objects.requireNonNullElse(type, "");
        }
    }

    public record SkillSummary(String name, String description) {
        public SkillSummary {
            if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
                throw new IllegalArgumentException("Invalid skill name: " + name);
            }
            description = Objects.requireNonNullElse(description, "");
        }
    }
}
