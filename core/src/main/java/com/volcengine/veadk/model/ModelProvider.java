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
package com.volcengine.veadk.model;

/** Supported providers for VeADK's user-facing Agent model configuration. */
public enum ModelProvider {
    ARK("ark"),
    OPENAI_COMPATIBLE("openai");

    private final String value;

    ModelProvider(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ModelProvider from(String value) {
        if (value == null || value.isBlank()) {
            return ARK;
        }

        return switch (value.trim().toLowerCase()) {
            case "ark" -> ARK;
            case "openai", "openai-compatible", "openai_compatible" -> OPENAI_COMPATIBLE;
            default -> throw new IllegalArgumentException("Unsupported model provider: " + value);
        };
    }
}
