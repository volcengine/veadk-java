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
import com.google.adk.agents.Callbacks;
import com.google.adk.agents.Instruction;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.codeexecutors.BaseCodeExecutor;
import com.google.adk.examples.BaseExampleProvider;
import com.google.adk.examples.Example;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.models.BaseLlm;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Schema;
import com.volcengine.veadk.config.VeADKConfig;
import com.volcengine.veadk.knowledgebase.BaseKnowledgebaseService;
import com.volcengine.veadk.model.ArkLlm;
import com.volcengine.veadk.processors.BaseRunProcessor;
import com.volcengine.veadk.processors.NoOpRunProcessor;
import com.volcengine.veadk.runner.Runner;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** VeADK agent facade that adds configuration, memory, processors, and metadata to ADK. */
public class Agent extends LlmAgent implements AgentComponentProvider {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    public static final String DEFAULT_NAME = "veAgent";
    public static final String DEFAULT_DESCRIPTION =
            "An AI agent developed by the VeADK team, specialized in data science, "
                    + "documentation, and software development.";
    public static final String DEFAULT_INSTRUCTION =
            "You are an AI agent created by the VeADK team. Use the available tools and "
                    + "resources to solve the user's task accurately.";

    private final String id;
    private final BaseKnowledgebaseService knowledgebase;
    private final BaseSessionService shortTermMemory;
    private final BaseMemoryService longTermMemory;
    private final BaseRunProcessor runProcessor;
    private final Object promptManager;
    private final List<Object> tracers;
    private final List<String> skills;
    private final List<BasePlugin> plugins;
    private final boolean enableAuthz;
    private final boolean autoSaveSession;
    private final SaveSessionPolicy saveSessionPolicy;

    protected Agent(Builder builder) {
        super(builder);
        id = builder.id;
        knowledgebase = builder.knowledgebase;
        shortTermMemory = builder.shortTermMemory;
        longTermMemory = builder.longTermMemory;
        runProcessor = builder.runProcessor;
        promptManager = builder.promptManager;
        tracers = List.copyOf(builder.tracers);
        skills = List.copyOf(builder.skills);
        plugins = List.copyOf(builder.plugins);
        enableAuthz = builder.enableAuthz;
        autoSaveSession = builder.autoSaveSession;
        saveSessionPolicy = builder.saveSessionPolicy;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Creates a builder whose Ark model is resolved from VeADK configuration. */
    public static Builder builder(VeADKConfig config) {
        Objects.requireNonNull(config, "config");
        VeADKConfig.ModelConfig model = config.model();
        String apiKey = config.require("MODEL_AGENT_API_KEY");
        return new Builder().model(new ArkLlm(model.name(), apiKey, model.apiBase()));
    }

    public String id() {
        return id;
    }

    public BaseKnowledgebaseService knowledgebase() {
        return knowledgebase;
    }

    public BaseSessionService shortTermMemory() {
        return shortTermMemory;
    }

    public BaseMemoryService longTermMemory() {
        return longTermMemory;
    }

    public BaseRunProcessor runProcessor() {
        return runProcessor;
    }

    public Object promptManager() {
        return promptManager;
    }

    public List<Object> tracers() {
        return tracers;
    }

    public List<String> skills() {
        return skills;
    }

    public List<BasePlugin> plugins() {
        return plugins;
    }

    public boolean enableAuthz() {
        return enableAuthz;
    }

    public boolean autoSaveSession() {
        return autoSaveSession;
    }

    public SaveSessionPolicy saveSessionPolicy() {
        return saveSessionPolicy;
    }

    public AgentMetadata metadata() {
        return AgentMetadata.from(this);
    }

    /** Creates a Runner using this agent's configured memory, plugins, and run processor. */
    public Runner newRunner() {
        BaseSessionService sessions =
                shortTermMemory == null ? new InMemorySessionService() : shortTermMemory;
        BaseMemoryService memory =
                longTermMemory == null ? new InMemoryMemoryService() : longTermMemory;
        return new Runner(
                this,
                name(),
                new InMemoryArtifactService(),
                sessions,
                memory,
                plugins,
                runProcessor);
    }

    @Override
    public List<AgentComponent> agentComponents() {
        List<AgentComponent> components = new ArrayList<>();
        addComponent(components, "knowledgebase", knowledgebase, "knowledgebase");
        addComponent(components, "short_term_memory", shortTermMemory, "shortTermMemory");
        addComponent(components, "long_term_memory", longTermMemory, "longTermMemory");
        addComponent(components, "prompt_manager", promptManager, "promptManager");
        if (!(runProcessor instanceof NoOpRunProcessor)) {
            addComponent(components, "run_processor", runProcessor, "runProcessor");
        }
        tracers.forEach(tracer -> addComponent(components, "tracer", tracer, "tracers"));
        plugins.forEach(plugin -> addComponent(components, "plugin", plugin, "plugins"));
        return List.copyOf(components);
    }

    @Override
    public List<AgentMetadata.SkillSummary> agentSkills() {
        return skills.stream()
                .filter(name -> name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
                .map(name -> new AgentMetadata.SkillSummary(name, ""))
                .toList();
    }

    private static void addComponent(
            List<AgentComponent> components, String kind, Object component, String source) {
        if (component != null) {
            components.add(
                    new AgentComponent(kind, component.getClass().getSimpleName(), source, "", ""));
        }
    }

    public static class Builder extends LlmAgent.Builder {

        private String id = UUID.randomUUID().toString().substring(0, 8);
        private BaseKnowledgebaseService knowledgebase;
        private BaseSessionService shortTermMemory;
        private BaseMemoryService longTermMemory;
        private BaseRunProcessor runProcessor = NoOpRunProcessor.INSTANCE;
        private Object promptManager;
        private List<Object> tracers = List.of();
        private List<String> skills = List.of();
        private List<BasePlugin> plugins = List.of();
        private boolean enableAuthz;
        private boolean autoSaveSession;
        private SaveSessionPolicy saveSessionPolicy = SaveSessionPolicy.defaults();
        private boolean saveSessionCallbackAttached;

        public Builder() {
            name(DEFAULT_NAME);
            description(DEFAULT_DESCRIPTION);
            instruction(DEFAULT_INSTRUCTION);
        }

        public Builder id(String id) {
            this.id = requireText(id, "id");
            return this;
        }

        public Builder knowledgebase(BaseKnowledgebaseService knowledgebase) {
            this.knowledgebase = knowledgebase;
            return this;
        }

        public Builder shortTermMemory(BaseSessionService shortTermMemory) {
            this.shortTermMemory = shortTermMemory;
            return this;
        }

        public Builder longTermMemory(BaseMemoryService longTermMemory) {
            this.longTermMemory = longTermMemory;
            return this;
        }

        public Builder runProcessor(BaseRunProcessor runProcessor) {
            this.runProcessor = Objects.requireNonNull(runProcessor, "runProcessor");
            return this;
        }

        public Builder promptManager(Object promptManager) {
            this.promptManager = promptManager;
            return this;
        }

        public Builder tracers(List<?> tracers) {
            this.tracers = List.copyOf(tracers);
            return this;
        }

        public Builder tracers(Object... tracers) {
            return tracers(Arrays.asList(tracers));
        }

        public Builder skills(List<String> skills) {
            this.skills = List.copyOf(skills);
            return this;
        }

        public Builder skills(String... skills) {
            return skills(Arrays.asList(skills));
        }

        public Builder plugins(List<? extends BasePlugin> plugins) {
            this.plugins = List.copyOf(plugins);
            return this;
        }

        public Builder plugins(BasePlugin... plugins) {
            return plugins(Arrays.asList(plugins));
        }

        public Builder enableAuthz(boolean enableAuthz) {
            this.enableAuthz = enableAuthz;
            return this;
        }

        public Builder autoSaveSession(boolean autoSaveSession) {
            this.autoSaveSession = autoSaveSession;
            return this;
        }

        public Builder saveSessionPolicy(SaveSessionPolicy saveSessionPolicy) {
            this.saveSessionPolicy = Objects.requireNonNull(saveSessionPolicy, "saveSessionPolicy");
            return this;
        }

        @Override
        public Builder name(String name) {
            super.name(name);
            return this;
        }

        @Override
        public Builder description(String description) {
            super.description(description);
            return this;
        }

        @Override
        public Builder subAgents(List<? extends BaseAgent> subAgents) {
            super.subAgents(subAgents);
            return this;
        }

        @Override
        public Builder subAgents(BaseAgent... subAgents) {
            super.subAgents(subAgents);
            return this;
        }

        @Override
        public Builder model(String model) {
            super.model(model);
            return this;
        }

        @Override
        public Builder model(BaseLlm model) {
            super.model(model);
            return this;
        }

        @Override
        public Builder instruction(Instruction instruction) {
            super.instruction(instruction);
            return this;
        }

        @Override
        public Builder instruction(String instruction) {
            super.instruction(instruction);
            return this;
        }

        @Override
        public Builder globalInstruction(Instruction instruction) {
            super.globalInstruction(instruction);
            return this;
        }

        @Override
        public Builder globalInstruction(String instruction) {
            super.globalInstruction(instruction);
            return this;
        }

        @Override
        public Builder tools(List<?> tools) {
            super.tools(tools);
            return this;
        }

        @Override
        public Builder tools(Object... tools) {
            super.tools(tools);
            return this;
        }

        @Override
        public Builder generateContentConfig(GenerateContentConfig config) {
            super.generateContentConfig(config);
            return this;
        }

        @Override
        public Builder exampleProvider(BaseExampleProvider provider) {
            super.exampleProvider(provider);
            return this;
        }

        @Override
        public Builder exampleProvider(List<Example> examples) {
            super.exampleProvider(examples);
            return this;
        }

        @Override
        public Builder exampleProvider(Example... examples) {
            super.exampleProvider(examples);
            return this;
        }

        @Override
        public Builder includeContents(IncludeContents includeContents) {
            super.includeContents(includeContents);
            return this;
        }

        @Override
        public Builder planning(boolean planning) {
            super.planning(planning);
            return this;
        }

        @Override
        public Builder maxSteps(int maxSteps) {
            super.maxSteps(maxSteps);
            return this;
        }

        @Override
        public Builder disallowTransferToParent(boolean disallow) {
            super.disallowTransferToParent(disallow);
            return this;
        }

        @Override
        public Builder disallowTransferToPeers(boolean disallow) {
            super.disallowTransferToPeers(disallow);
            return this;
        }

        @Override
        public Builder beforeAgentCallback(Callbacks.BeforeAgentCallback callback) {
            super.beforeAgentCallback(callback);
            return this;
        }

        @Override
        public Builder afterAgentCallback(Callbacks.AfterAgentCallback callback) {
            super.afterAgentCallback(callback);
            return this;
        }

        @Override
        public Builder beforeAgentCallbackSync(Callbacks.BeforeAgentCallbackSync callback) {
            super.beforeAgentCallbackSync(callback);
            return this;
        }

        @Override
        public Builder afterAgentCallbackSync(Callbacks.AfterAgentCallbackSync callback) {
            super.afterAgentCallbackSync(callback);
            return this;
        }

        @Override
        public Builder beforeModelCallback(Callbacks.BeforeModelCallback callback) {
            super.beforeModelCallback(callback);
            return this;
        }

        @Override
        public Builder beforeModelCallbackSync(Callbacks.BeforeModelCallbackSync callback) {
            super.beforeModelCallbackSync(callback);
            return this;
        }

        @Override
        public Builder afterModelCallback(Callbacks.AfterModelCallback callback) {
            super.afterModelCallback(callback);
            return this;
        }

        @Override
        public Builder afterModelCallbackSync(Callbacks.AfterModelCallbackSync callback) {
            super.afterModelCallbackSync(callback);
            return this;
        }

        @Override
        public Builder beforeToolCallback(Callbacks.BeforeToolCallback callback) {
            super.beforeToolCallback(callback);
            return this;
        }

        @Override
        public Builder beforeToolCallbackSync(Callbacks.BeforeToolCallbackSync callback) {
            super.beforeToolCallbackSync(callback);
            return this;
        }

        @Override
        public Builder afterToolCallback(Callbacks.AfterToolCallback callback) {
            super.afterToolCallback(callback);
            return this;
        }

        @Override
        public Builder afterToolCallbackSync(Callbacks.AfterToolCallbackSync callback) {
            super.afterToolCallbackSync(callback);
            return this;
        }

        @Override
        public Builder inputSchema(Schema schema) {
            super.inputSchema(schema);
            return this;
        }

        @Override
        public Builder outputSchema(Schema schema) {
            super.outputSchema(schema);
            return this;
        }

        @Override
        public Builder executor(Executor executor) {
            super.executor(executor);
            return this;
        }

        @Override
        public Builder outputKey(String outputKey) {
            super.outputKey(outputKey);
            return this;
        }

        @Override
        public Builder codeExecutor(BaseCodeExecutor codeExecutor) {
            super.codeExecutor(codeExecutor);
            return this;
        }

        @Override
        public Agent build() {
            configureAutoSaveSession();
            validate();
            return new Agent(this);
        }

        private void configureAutoSaveSession() {
            if (!autoSaveSession || saveSessionCallbackAttached) {
                return;
            }
            if (longTermMemory == null) {
                log.warn(
                        "autoSaveSession is enabled, but longTermMemory is not configured; "
                                + "the save callback was not installed");
                return;
            }
            ImmutableList.Builder<Callbacks.AfterAgentCallback> callbacks = ImmutableList.builder();
            if (this.afterAgentCallback != null) {
                callbacks.addAll(this.afterAgentCallback);
            }
            this.afterAgentCallback =
                    callbacks
                            .add(new SaveSessionToMemoryCallback(saveSessionPolicy, longTermMemory))
                            .build();
            saveSessionCallbackAttached = true;
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value;
        }
    }
}
