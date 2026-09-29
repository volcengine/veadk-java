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
import com.google.adk.agents.Callbacks;
import com.google.adk.agents.Instruction;
import com.google.adk.agents.LlmAgent;
import com.google.adk.codeexecutors.BaseCodeExecutor;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.models.BaseLlm;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Schema;
import com.volcengine.veadk.knowledgebase.BaseKnowledgebaseService;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/** VeADK Agent facade that keeps ADK Java's LlmAgent execution path. */
public final class Agent extends LlmAgent {

    public static final String DEFAULT_NAME = "veAgent";
    public static final String DEFAULT_DESCRIPTION = "A helpful VeADK agent.";
    public static final String DEFAULT_INSTRUCTION = "You are a helpful assistant.";
    public static final String DEFAULT_MODEL_NAME = "doubao-seed-2-1-pro-260628";

    private final BaseMemoryService longTermMemoryService;
    private final BaseKnowledgebaseService knowledgebaseService;
    private final boolean autoSaveSession;
    private final String veadkModelName;
    private final AgentMetadataSnapshot metadataSnapshot;

    private Agent(Builder builder) {
        super(builder);
        this.longTermMemoryService = builder.longTermMemoryService;
        this.knowledgebaseService = builder.knowledgebaseService;
        this.autoSaveSession = builder.autoSaveSession;
        this.veadkModelName = Objects.requireNonNullElse(builder.veadkModelName, "");
        this.metadataSnapshot = builder.metadataSnapshot(name(), description());
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<BaseMemoryService> longTermMemoryService() {
        return Optional.ofNullable(longTermMemoryService);
    }

    public Optional<BaseKnowledgebaseService> knowledgebaseService() {
        return Optional.ofNullable(knowledgebaseService);
    }

    public boolean autoSaveSession() {
        return autoSaveSession;
    }

    public String veadkModelName() {
        return veadkModelName;
    }

    public AgentMetadataSnapshot metadataSnapshot() {
        return metadataSnapshot;
    }

    /** Builder for {@link Agent}. */
    public static final class Builder extends LlmAgent.Builder {

        private BaseMemoryService longTermMemoryService;
        private BaseKnowledgebaseService knowledgebaseService;
        private boolean autoSaveSession;
        private String veadkModelName = "";
        private String instructionSummary = "";
        private List<String> explicitToolNames = List.of();
        private List<String> autoToolNames = List.of();

        public Builder() {
            name(DEFAULT_NAME);
            description(DEFAULT_DESCRIPTION);
            instruction(DEFAULT_INSTRUCTION);
            modelName(DEFAULT_MODEL_NAME);
        }

        public Builder modelName(String modelName) {
            return model(modelName);
        }

        public Builder knowledgebase(BaseKnowledgebaseService service) {
            this.knowledgebaseService = Objects.requireNonNull(service, "service must be set.");
            return this;
        }

        public Builder longTermMemory(BaseMemoryService service) {
            this.longTermMemoryService = Objects.requireNonNull(service, "service must be set.");
            return this;
        }

        public Builder autoSaveSession(boolean enabled) {
            this.autoSaveSession = enabled;
            return this;
        }

        public Builder modelApiKey(String apiKey) {
            throw new UnsupportedOperationException(
                    "modelApiKey is reserved for the ArkLlm configuration task and is not wired in"
                            + " Agent PR-0. Use model(BaseLlm) for explicit model instances.");
        }

        public Builder runtime(String runtime) {
            throw unsupportedPythonOption("runtime");
        }

        public Builder enableResponses(boolean enabled) {
            throw unsupportedPythonOption("enableResponses");
        }

        public Builder skills(List<?> skills) {
            throw unsupportedPythonOption("skills");
        }

        public Builder skillsMode(String skillsMode) {
            throw unsupportedPythonOption("skillsMode");
        }

        public Builder enableA2ui(boolean enabled) {
            throw unsupportedPythonOption("enableA2ui");
        }

        public Builder enableTunnel(boolean enabled) {
            throw unsupportedPythonOption("enableTunnel");
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
        public Builder beforeAgentCallback(Callbacks.BeforeAgentCallback beforeAgentCallback) {
            super.beforeAgentCallback(beforeAgentCallback);
            return this;
        }

        @Override
        public Builder beforeAgentCallbackSync(
                Callbacks.BeforeAgentCallbackSync beforeAgentCallbackSync) {
            super.beforeAgentCallbackSync(beforeAgentCallbackSync);
            return this;
        }

        @Override
        public Builder afterAgentCallback(Callbacks.AfterAgentCallback afterAgentCallback) {
            super.afterAgentCallback(afterAgentCallback);
            return this;
        }

        @Override
        public Builder afterAgentCallbackSync(
                Callbacks.AfterAgentCallbackSync afterAgentCallbackSync) {
            super.afterAgentCallbackSync(afterAgentCallbackSync);
            return this;
        }

        @Override
        public Builder model(String model) {
            String resolvedModelName = requireText(model, "model must be set.");
            super.model(resolvedModelName);
            this.veadkModelName = resolvedModelName;
            return this;
        }

        @Override
        public Builder model(BaseLlm model) {
            BaseLlm resolvedModel = Objects.requireNonNull(model, "model must be set.");
            super.model(resolvedModel);
            this.veadkModelName = Objects.requireNonNullElse(resolvedModel.model(), "");
            return this;
        }

        @Override
        public Builder instruction(Instruction instruction) {
            super.instruction(instruction);
            this.instructionSummary = summarizeInstruction(instruction);
            return this;
        }

        @Override
        public Builder instruction(String instruction) {
            super.instruction(instruction);
            this.instructionSummary = Objects.requireNonNullElse(instruction, "");
            return this;
        }

        @Override
        public Builder globalInstruction(Instruction globalInstruction) {
            super.globalInstruction(globalInstruction);
            return this;
        }

        @Override
        public Builder globalInstruction(String globalInstruction) {
            super.globalInstruction(globalInstruction);
            return this;
        }

        @Override
        public Builder tools(List<?> tools) {
            super.tools(tools);
            this.explicitToolNames = toolNames(tools);
            return this;
        }

        @Override
        public Builder tools(Object... tools) {
            super.tools(tools);
            this.explicitToolNames = toolNames(Arrays.asList(tools));
            return this;
        }

        @Override
        public Builder generateContentConfig(GenerateContentConfig generateContentConfig) {
            super.generateContentConfig(generateContentConfig);
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
        public Builder disallowTransferToParent(boolean disallowTransferToParent) {
            super.disallowTransferToParent(disallowTransferToParent);
            return this;
        }

        @Override
        public Builder disallowTransferToPeers(boolean disallowTransferToPeers) {
            super.disallowTransferToPeers(disallowTransferToPeers);
            return this;
        }

        @Override
        public Builder clearBeforeModelCallbacks() {
            super.clearBeforeModelCallbacks();
            return this;
        }

        @Override
        public Builder beforeModelCallback(Callbacks.BeforeModelCallback beforeModelCallback) {
            super.beforeModelCallback(beforeModelCallback);
            return this;
        }

        @Override
        public Builder beforeModelCallbackSync(
                Callbacks.BeforeModelCallbackSync beforeModelCallbackSync) {
            super.beforeModelCallbackSync(beforeModelCallbackSync);
            return this;
        }

        @Override
        public Builder afterModelCallback(Callbacks.AfterModelCallback afterModelCallback) {
            super.afterModelCallback(afterModelCallback);
            return this;
        }

        @Override
        public Builder afterModelCallbackSync(
                Callbacks.AfterModelCallbackSync afterModelCallbackSync) {
            super.afterModelCallbackSync(afterModelCallbackSync);
            return this;
        }

        @Override
        public Builder onModelErrorCallback(Callbacks.OnModelErrorCallback onModelErrorCallback) {
            super.onModelErrorCallback(onModelErrorCallback);
            return this;
        }

        @Override
        public Builder onModelErrorCallbackSync(
                Callbacks.OnModelErrorCallbackSync onModelErrorCallbackSync) {
            super.onModelErrorCallbackSync(onModelErrorCallbackSync);
            return this;
        }

        @Override
        public Builder beforeToolCallback(Callbacks.BeforeToolCallback beforeToolCallback) {
            super.beforeToolCallback(beforeToolCallback);
            return this;
        }

        @Override
        public Builder beforeToolCallbackSync(
                Callbacks.BeforeToolCallbackSync beforeToolCallbackSync) {
            super.beforeToolCallbackSync(beforeToolCallbackSync);
            return this;
        }

        @Override
        public Builder afterToolCallback(Callbacks.AfterToolCallback afterToolCallback) {
            super.afterToolCallback(afterToolCallback);
            return this;
        }

        @Override
        public Builder afterToolCallbackSync(
                Callbacks.AfterToolCallbackSync afterToolCallbackSync) {
            super.afterToolCallbackSync(afterToolCallbackSync);
            return this;
        }

        @Override
        public Builder onToolErrorCallback(Callbacks.OnToolErrorCallback onToolErrorCallback) {
            super.onToolErrorCallback(onToolErrorCallback);
            return this;
        }

        @Override
        public Builder onToolErrorCallbackSync(
                Callbacks.OnToolErrorCallbackSync onToolErrorCallbackSync) {
            super.onToolErrorCallbackSync(onToolErrorCallbackSync);
            return this;
        }

        @Override
        public Builder inputSchema(Schema inputSchema) {
            super.inputSchema(inputSchema);
            return this;
        }

        @Override
        public Builder outputSchema(Schema outputSchema) {
            super.outputSchema(outputSchema);
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
            validate();
            return new Agent(this);
        }

        private AgentMetadataSnapshot metadataSnapshot(String name, String description) {
            return new AgentMetadataSnapshot(
                    name,
                    description,
                    instructionSummary,
                    veadkModelName,
                    explicitToolNames,
                    autoToolNames,
                    knowledgebaseService != null,
                    longTermMemoryService != null,
                    autoSaveSession);
        }

        private static List<String> toolNames(List<?> tools) {
            Objects.requireNonNull(tools, "tools must be set.");
            return tools.stream().map(Builder::toolName).toList();
        }

        private static String toolName(Object tool) {
            Objects.requireNonNull(tool, "tool must not be null.");
            if (tool instanceof BaseTool baseTool) {
                return baseTool.name();
            }
            if (tool instanceof BaseToolset baseToolset) {
                return baseToolset.getClass().getSimpleName();
            }
            return tool.getClass().getSimpleName();
        }

        private static String summarizeInstruction(Instruction instruction) {
            if (instruction instanceof Instruction.Static staticInstruction) {
                return staticInstruction.instruction();
            }
            if (instruction == null) {
                return "";
            }
            return instruction.getClass().getSimpleName();
        }

        private static String requireText(String value, String message) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(message);
            }
            return value;
        }

        private static UnsupportedOperationException unsupportedPythonOption(String optionName) {
            return new UnsupportedOperationException(
                    optionName
                            + " is not supported in Agent PR-0. The Java Agent facade keeps this"
                            + " Python-side option fail-fast until a typed Java design is added.");
        }
    }
}
