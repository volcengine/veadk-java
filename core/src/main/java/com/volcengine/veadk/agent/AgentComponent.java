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

import java.util.Objects;

/** Stable, serialization-friendly description of a component mounted on an agent. */
public record AgentComponent(
        String kind, String name, String source, String backend, String description) {

    public AgentComponent {
        kind = requireText(kind, "kind");
        name = requireText(name, "name");
        source = Objects.requireNonNullElse(source, "");
        backend = Objects.requireNonNullElse(backend, "");
        description = Objects.requireNonNullElse(description, "");
    }

    public AgentComponent(String kind, String name) {
        this(kind, name, "", "", "");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
