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
import com.google.adk.skills.LocalSkillSource;
import com.google.adk.skills.SkillSource;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.LoadMemoryTool;
import com.google.adk.tools.skills.SkillToolset;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Schema;
import com.volcengine.veadk.knowledgebase.BaseKnowledgebaseService;
import com.volcengine.veadk.memory.SaveSessionToMemoryCallback;
import com.volcengine.veadk.memory.ShortTermMemory;
import com.volcengine.veadk.model.ArkLlm;
import com.volcengine.veadk.model.ArkLlmConfig;
import com.volcengine.veadk.model.ModelProvider;
import com.volcengine.veadk.model.OpenAiCompatibleLlm;
import com.volcengine.veadk.model.OpenAiCompatibleLlmConfig;
import com.volcengine.veadk.skills.CompositeSkillSource;
import com.volcengine.veadk.skills.SingleSkillDirectorySource;
import com.volcengine.veadk.tools.builtin.knowledgebase.LoadKnowledgebaseTool;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/** VeADK Agent that keeps ADK Java's LlmAgent execution path. */
public final class Agent extends LlmAgent {

    public static final String DEFAULT_NAME = "veAgent";
    public static final String DEFAULT_DESCRIPTION = "A helpful VeADK agent.";
    public static final String DEFAULT_INSTRUCTION = "You are a helpful assistant.";
    public static final String DEFAULT_MODEL_NAME = "doubao-seed-2-1-pro-260628";
    public static final String AUTO_TOOL_METADATA_KEY = "veadk.autoTool";
    public static final String AUTO_TOOL_SOURCE_METADATA_KEY = "veadk.autoToolSource";
    private static final String SKILLS_MODE_LOCAL = "local";
    private static final String SKILLS_MODE_SKILLS_SANDBOX = "skills_sandbox";

    private final BaseMemoryService longTermMemoryService;
    private final ShortTermMemory shortTermMemory;
    private final BaseKnowledgebaseService knowledgebaseService;
    private final boolean autoSaveSession;
    private final String veadkModelName;
    private final AgentMetadataSnapshot metadataSnapshot;
    private final List<AutoCloseable> closeableTools;

    private Agent(Builder builder) {
        super(builder);
        this.longTermMemoryService = builder.longTermMemoryService;
        this.shortTermMemory = builder.shortTermMemory;
        this.knowledgebaseService = builder.knowledgebaseService;
        this.autoSaveSession = builder.autoSaveSession;
        this.veadkModelName = Objects.requireNonNullElse(builder.veadkModelName, "");
        this.metadataSnapshot = builder.metadataSnapshot(name(), description());
        this.closeableTools =
                builder.explicitTools.stream()
                        .filter(AutoCloseable.class::isInstance)
                        .map(AutoCloseable.class::cast)
                        .toList();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<BaseMemoryService> longTermMemoryService() {
        return Optional.ofNullable(longTermMemoryService);
    }

    public Optional<ShortTermMemory> shortTermMemory() {
        return Optional.ofNullable(shortTermMemory);
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

    @Override
    public Completable close() {
        Completable closeModel =
                Completable.fromAction(
                        () ->
                                model().flatMap(com.google.adk.models.Model::model)
                                        .filter(AutoCloseable.class::isInstance)
                                        .map(AutoCloseable.class::cast)
                                        .ifPresent(
                                                closeable ->
                                                        closeAutoCloseable(closeable, "model")));
        Completable closeTools =
                Completable.fromAction(
                        () ->
                                closeableTools.forEach(
                                        closeable -> closeAutoCloseable(closeable, "tool")));
        return Completable.mergeArray(super.close(), closeModel, closeTools);
    }

    private static void closeAutoCloseable(AutoCloseable closeable, String resourceType) {
        try {
            closeable.close();
        } catch (Exception e) {
            throw new RuntimeException("Failed to close " + resourceType, e);
        }
    }

    /** Builder for {@link Agent}. */
    public static final class Builder extends LlmAgent.Builder {

        private BaseMemoryService longTermMemoryService;
        private ShortTermMemory shortTermMemory;
        private BaseKnowledgebaseService knowledgebaseService;
        private boolean autoSaveSession;
        private String veadkModelName = "";
        private String instructionSummary = "";
        private List<String> explicitToolNames = List.of();
        private List<String> autoToolNames = List.of();
        private boolean explicitModelConfigured;
        private ModelProvider modelProvider;
        private String modelApiKey;
        private String modelApiBase;
        private String modelThinking;
        private SaveSessionToMemoryCallback.AutoSavePolicy autoSaveMemoryPolicy =
                SaveSessionToMemoryCallback.AutoSavePolicy.fromEnv();
        private List<Object> explicitTools = List.of();
        private List<Object> localSkills = List.of();
        private String skillsMode = "local";
        private List<Callbacks.AfterAgentCallback> explicitAfterAgentCallbacks = List.of();

        public Builder() {
            name(DEFAULT_NAME);
            description(DEFAULT_DESCRIPTION);
            instruction(DEFAULT_INSTRUCTION);
            modelName(DEFAULT_MODEL_NAME);
        }

        public Builder modelName(String modelName) {
            return model(modelName);
        }

        public Builder modelProvider(String provider) {
            this.modelProvider = ModelProvider.from(provider);
            return this;
        }

        public Builder modelProvider(ModelProvider provider) {
            this.modelProvider = Objects.requireNonNull(provider, "provider must be set.");
            return this;
        }

        public Builder knowledgebase(BaseKnowledgebaseService service) {
            this.knowledgebaseService = Objects.requireNonNull(service, "service must be set.");
            return this;
        }

        public Builder longTermMemory(BaseMemoryService service) {
            this.longTermMemoryService = Objects.requireNonNull(service, "service must be set.");
            return this;
        }

        public Builder shortTermMemory(ShortTermMemory shortTermMemory) {
            this.shortTermMemory =
                    Objects.requireNonNull(shortTermMemory, "shortTermMemory must be set.");
            return this;
        }

        public Builder autoSaveSession(boolean enabled) {
            this.autoSaveSession = enabled;
            return this;
        }

        public Builder autoSaveMemoryPolicy(
                SaveSessionToMemoryCallback.AutoSavePolicy autoSaveMemoryPolicy) {
            this.autoSaveMemoryPolicy =
                    Objects.requireNonNull(
                            autoSaveMemoryPolicy, "autoSaveMemoryPolicy must be set.");
            return this;
        }

        public Builder modelApiKey(String apiKey) {
            this.modelApiKey = apiKey;
            return this;
        }

        public Builder modelApiBase(String apiBase) {
            this.modelApiBase = apiBase;
            return this;
        }

        public Builder modelBaseUrl(String baseUrl) {
            return modelApiBase(baseUrl);
        }

        public Builder modelThinking(String thinking) {
            this.modelThinking = thinking;
            return this;
        }

        public Builder runtime(String runtime) {
            throw unsupportedPythonOption("runtime");
        }

        public Builder enableResponses(boolean enabled) {
            throw unsupportedPythonOption("enableResponses");
        }

        public Builder skills(List<?> skills) {
            this.localSkills = List.copyOf(Objects.requireNonNull(skills, "skills must be set."));
            return this;
        }

        public Builder skills(Object... skills) {
            return skills(Arrays.asList(Objects.requireNonNull(skills, "skills must be set.")));
        }

        public Builder skillsMode(String skillsMode) {
            String resolvedMode =
                    requireText(skillsMode, "skillsMode must be set.").toLowerCase(Locale.ROOT);
            if (!SKILLS_MODE_LOCAL.equals(resolvedMode)
                    && !SKILLS_MODE_SKILLS_SANDBOX.equals(resolvedMode)) {
                throw unsupportedPythonOption("skillsMode=" + skillsMode);
            }
            this.skillsMode = resolvedMode;
            return this;
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
        @SuppressWarnings({"rawtypes", "unchecked"})
        public Builder beforeAgentCallback(List beforeAgentCallback) {
            // ADK's callback base marker type is package-private, so this override must keep
            // the inherited erased signature instead of exposing that marker in VeADK's API.
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
            Callbacks.AfterAgentCallback resolvedCallback =
                    Objects.requireNonNull(afterAgentCallback, "afterAgentCallback must be set.");
            super.afterAgentCallback(resolvedCallback);
            this.explicitAfterAgentCallbacks = List.of(resolvedCallback);
            return this;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public Builder afterAgentCallback(List afterAgentCallback) {
            // ADK's callback base marker type is package-private, so this override must keep
            // the inherited erased signature instead of exposing that marker in VeADK's API.
            this.explicitAfterAgentCallbacks = normalizeAfterAgentCallbacks(afterAgentCallback);
            super.afterAgentCallback(afterAgentCallback);
            return this;
        }

        @Override
        public Builder afterAgentCallbackSync(
                Callbacks.AfterAgentCallbackSync afterAgentCallbackSync) {
            Callbacks.AfterAgentCallbackSync resolvedCallback =
                    Objects.requireNonNull(
                            afterAgentCallbackSync, "afterAgentCallbackSync must be set.");
            Callbacks.AfterAgentCallback callback =
                    callbackContext -> Maybe.fromOptional(resolvedCallback.call(callbackContext));
            super.afterAgentCallback(callback);
            this.explicitAfterAgentCallbacks = List.of(callback);
            return this;
        }

        @Override
        public Builder model(String model) {
            String resolvedModelName = requireText(model, "model must be set.");
            this.veadkModelName = resolvedModelName;
            this.explicitModelConfigured = false;
            return this;
        }

        @Override
        public Builder model(BaseLlm model) {
            BaseLlm resolvedModel = Objects.requireNonNull(model, "model must be set.");
            super.model(resolvedModel);
            this.veadkModelName = Objects.requireNonNullElse(resolvedModel.model(), "");
            this.explicitModelConfigured = true;
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
            List<?> resolvedTools =
                    List.copyOf(Objects.requireNonNull(tools, "tools must be set."));
            super.tools(resolvedTools);
            this.explicitTools = List.copyOf(resolvedTools);
            this.explicitToolNames = toolNames(resolvedTools);
            return this;
        }

        @Override
        public Builder tools(Object... tools) {
            List<?> resolvedTools =
                    List.copyOf(Arrays.asList(Objects.requireNonNull(tools, "tools must be set.")));
            super.tools(resolvedTools);
            this.explicitTools = List.copyOf(resolvedTools);
            this.explicitToolNames = toolNames(resolvedTools);
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
            prepareAutoComponents();
            configureModel();
            validate();
            return new Agent(this);
        }

        private void configureModel() {
            if (explicitModelConfigured) {
                return;
            }
            ResolvedModel resolvedModel = resolveModel(modelProvider, veadkModelName);
            this.veadkModelName = resolvedModel.modelName();
            switch (resolvedModel.provider()) {
                case ARK ->
                        super.model(
                                new ArkLlm(
                                        ArkLlmConfig.builder()
                                                .modelName(resolvedModel.modelName())
                                                .apiKey(modelApiKey)
                                                .apiBase(modelApiBase)
                                                .thinking(modelThinking)
                                                .build()));
                case OPENAI_COMPATIBLE ->
                        super.model(
                                new OpenAiCompatibleLlm(
                                        OpenAiCompatibleLlmConfig.builder()
                                                .modelName(resolvedModel.modelName())
                                                .apiKey(modelApiKey)
                                                .baseUrl(modelApiBase)
                                                .build()));
            }
        }

        private static ResolvedModel resolveModel(
                ModelProvider explicitProvider, String configuredModelName) {
            String modelName = requireText(configuredModelName, "model must be set.").trim();
            if (explicitProvider != null) {
                return new ResolvedModel(explicitProvider, modelName);
            }
            int providerSeparator = modelName.indexOf('/');
            if (providerSeparator > 0 && providerSeparator < modelName.length() - 1) {
                String prefix = modelName.substring(0, providerSeparator);
                if (isSupportedProviderPrefix(prefix)) {
                    return new ResolvedModel(
                            ModelProvider.from(prefix), modelName.substring(providerSeparator + 1));
                }
            }
            return new ResolvedModel(ModelProvider.OPENAI_COMPATIBLE, modelName);
        }

        private static boolean isSupportedProviderPrefix(String prefix) {
            return "ark".equalsIgnoreCase(prefix)
                    || "openai".equalsIgnoreCase(prefix)
                    || "openai-compatible".equalsIgnoreCase(prefix)
                    || "openai_compatible".equalsIgnoreCase(prefix);
        }

        private void prepareAutoComponents() {
            List<Object> tools = new ArrayList<>(explicitTools);
            List<String> generatedAutoToolNames = new ArrayList<>();

            if (knowledgebaseService != null && !containsToolNamed(tools, "loadKnowledgebase")) {
                BaseTool knowledgebaseTool =
                        markAutoTool(
                                new LoadKnowledgebaseTool(knowledgebaseService), "knowledgebase");
                tools.add(knowledgebaseTool);
                generatedAutoToolNames.add(knowledgebaseTool.name());
            }

            if (longTermMemoryService != null && !containsToolNamed(tools, "loadMemory")) {
                BaseTool memoryTool = markAutoTool(new LoadMemoryTool(), "memory");
                tools.add(memoryTool);
                generatedAutoToolNames.add(memoryTool.name());
            }

            if (!localSkills.isEmpty()) {
                if (SKILLS_MODE_LOCAL.equals(skillsMode)) {
                    if (!containsToolset(tools, SkillToolset.class)) {
                        BaseToolset skillToolset = new SkillToolset(createSkillSource(localSkills));
                        tools.add(skillToolset);
                        generatedAutoToolNames.add(toolName(skillToolset));
                    }
                } else if (SKILLS_MODE_SKILLS_SANDBOX.equals(skillsMode)) {
                    validateSkillsSandboxSources(localSkills);
                    if (!containsToolNamed(tools, "execute_skills")) {
                        throw new IllegalArgumentException(
                                "skills_sandbox requires an explicit execute_skills tool.");
                    }
                } else {
                    throw unsupportedPythonOption("skillsMode=" + skillsMode);
                }
            } else if (SKILLS_MODE_SKILLS_SANDBOX.equals(skillsMode)) {
                throw new IllegalArgumentException(
                        "skills must contain at least one Skill Space ID when skillsMode is"
                                + " skills_sandbox.");
            }

            this.autoToolNames = List.copyOf(generatedAutoToolNames);
            super.tools(tools);

            List<Callbacks.AfterAgentCallback> afterAgentCallbacks =
                    new ArrayList<>(explicitAfterAgentCallbacks);
            if (autoSaveSession && !containsSaveSessionCallback(afterAgentCallbacks)) {
                afterAgentCallbacks.add(new SaveSessionToMemoryCallback(autoSaveMemoryPolicy));
            }
            this.afterAgentCallback = ImmutableList.copyOf(afterAgentCallbacks);
        }

        private static BaseTool markAutoTool(BaseTool tool, String source) {
            tool.setCustomMetadata(AUTO_TOOL_METADATA_KEY, true);
            tool.setCustomMetadata(AUTO_TOOL_SOURCE_METADATA_KEY, source);
            return tool;
        }

        private static boolean containsToolNamed(List<?> tools, String name) {
            return tools.stream()
                    .filter(BaseTool.class::isInstance)
                    .map(BaseTool.class::cast)
                    .anyMatch(tool -> tool.name().equals(name));
        }

        private static boolean containsToolset(
                List<?> tools, Class<? extends BaseToolset> toolsetType) {
            return tools.stream().anyMatch(toolsetType::isInstance);
        }

        private static SkillSource createSkillSource(List<?> skills) {
            List<SkillSource> sources = skills.stream().map(Builder::createSkillSource).toList();
            return sources.size() == 1 ? sources.get(0) : new CompositeSkillSource(sources);
        }

        private static SkillSource createSkillSource(Object skill) {
            Objects.requireNonNull(skill, "skill must not be null.");
            if (skill instanceof SkillSource skillSource) {
                return skillSource;
            }
            if (skill instanceof Path path) {
                return createLocalSkillSource(path);
            }
            if (skill instanceof String path) {
                return createLocalSkillSource(
                        Path.of(requireText(path, "skill path must be set.")));
            }
            throw new IllegalArgumentException(
                    "skills entries must be String, Path, or SkillSource, but got "
                            + skill.getClass().getName());
        }

        private static SkillSource createLocalSkillSource(Path path) {
            Path resolvedPath =
                    Objects.requireNonNull(path, "skill path must be set.")
                            .toAbsolutePath()
                            .normalize();
            if (Files.isRegularFile(resolvedPath)) {
                String fileName = resolvedPath.getFileName().toString();
                if (!"SKILL.md".equals(fileName) && !"skill.md".equals(fileName)) {
                    throw new IllegalArgumentException(
                            "skill file path must point to SKILL.md or skill.md: " + resolvedPath);
                }
                return new SingleSkillDirectorySource(resolvedPath.getParent());
            }
            if (!Files.isDirectory(resolvedPath)) {
                throw new IllegalArgumentException("skill path does not exist: " + resolvedPath);
            }
            if (Files.isRegularFile(resolvedPath.resolve("SKILL.md"))
                    || Files.isRegularFile(resolvedPath.resolve("skill.md"))) {
                return new SingleSkillDirectorySource(resolvedPath);
            }
            return new LocalSkillSource(resolvedPath);
        }

        private static void validateSkillsSandboxSources(List<?> skills) {
            for (Object skill : skills) {
                if (!(skill instanceof String skillSpaceId)) {
                    throw new IllegalArgumentException(
                            "skills_sandbox skills entries must be Skill Space ID strings.");
                }
                String resolvedSkillSpaceId =
                        requireText(skillSpaceId, "skill space ID must be set.");
                if (!resolvedSkillSpaceId.startsWith("ss-")) {
                    throw new IllegalArgumentException(
                            "skills_sandbox skill space ID must start with ss-: "
                                    + resolvedSkillSpaceId);
                }
            }
        }

        private static boolean containsSaveSessionCallback(
                List<Callbacks.AfterAgentCallback> callbacks) {
            return callbacks.stream().anyMatch(SaveSessionToMemoryCallback.class::isInstance);
        }

        @SuppressWarnings("rawtypes")
        private static List<Callbacks.AfterAgentCallback> normalizeAfterAgentCallbacks(
                List callbacks) {
            if (callbacks == null) {
                return List.of();
            }
            List<Callbacks.AfterAgentCallback> normalizedCallbacks = new ArrayList<>();
            for (Object callback : callbacks) {
                if (callback instanceof Callbacks.AfterAgentCallback afterAgentCallback) {
                    normalizedCallbacks.add(afterAgentCallback);
                } else if (callback instanceof Callbacks.AfterAgentCallbackSync syncCallback) {
                    normalizedCallbacks.add(
                            callbackContext ->
                                    Maybe.fromOptional(syncCallback.call(callbackContext)));
                }
            }
            return List.copyOf(normalizedCallbacks);
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
                    shortTermMemory != null,
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
                            + " is not supported by the current Java Agent. The Java Agent keeps"
                            + " this Python-side option fail-fast until a typed Java design is"
                            + " added.");
        }

        private record ResolvedModel(ModelProvider provider, String modelName) {}
    }
}
