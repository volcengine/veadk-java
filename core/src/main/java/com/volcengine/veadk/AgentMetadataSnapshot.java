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

/** Immutable metadata captured from the VeADK Agent builder. */
public final class AgentMetadataSnapshot {

    private final String name;
    private final String description;
    private final String instructionSummary;
    private final String modelName;
    private final List<String> explicitToolNames;
    private final List<String> autoToolNames;
    private final boolean hasKnowledgebase;
    private final boolean hasLongTermMemory;
    private final boolean autoSaveSession;

    public AgentMetadataSnapshot(
            String name,
            String description,
            String instructionSummary,
            String modelName,
            List<String> explicitToolNames,
            List<String> autoToolNames,
            boolean hasKnowledgebase,
            boolean hasLongTermMemory,
            boolean autoSaveSession) {
        this.name = Objects.requireNonNullElse(name, "");
        this.description = Objects.requireNonNullElse(description, "");
        this.instructionSummary = Objects.requireNonNullElse(instructionSummary, "");
        this.modelName = Objects.requireNonNullElse(modelName, "");
        this.explicitToolNames = List.copyOf(Objects.requireNonNull(explicitToolNames));
        this.autoToolNames = List.copyOf(Objects.requireNonNull(autoToolNames));
        this.hasKnowledgebase = hasKnowledgebase;
        this.hasLongTermMemory = hasLongTermMemory;
        this.autoSaveSession = autoSaveSession;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String instructionSummary() {
        return instructionSummary;
    }

    public String modelName() {
        return modelName;
    }

    public List<String> explicitToolNames() {
        return explicitToolNames;
    }

    public List<String> autoToolNames() {
        return autoToolNames;
    }

    public boolean hasKnowledgebase() {
        return hasKnowledgebase;
    }

    public boolean hasLongTermMemory() {
        return hasLongTermMemory;
    }

    public boolean autoSaveSession() {
        return autoSaveSession;
    }
}
