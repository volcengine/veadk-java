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

import com.volcengine.veadk.utils.EnvUtil;
import org.apache.commons.lang3.StringUtils;

/** Typed configuration for {@link ArkLlm}. */
public final class ArkLlmConfig {

    public static final String DEFAULT_API_BASE = "https://ark.cn-beijing.volces.com/api/v3/";
    public static final String MODEL_AGENT_API_BASE = "MODEL_AGENT_API_BASE";

    private final String modelName;
    private final String apiKey;
    private final String apiBase;
    private final String thinking;

    private ArkLlmConfig(Builder builder) {
        this.modelName = requireText(builder.modelName, "modelName must be set.");
        this.apiKey = resolveApiKey(builder.apiKey);
        this.apiBase = resolveApiBase(builder.apiBase);
        this.thinking = StringUtils.trimToNull(builder.thinking);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ArkLlmConfig fromEnv(String modelName) {
        return builder().modelName(modelName).build();
    }

    public String getModelName() {
        return modelName;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getApiBase() {
        return apiBase;
    }

    public String getThinking() {
        return thinking;
    }

    private static String resolveApiKey(String explicitApiKey) {
        if (StringUtils.isNotBlank(explicitApiKey)) {
            return explicitApiKey.trim();
        }
        return EnvUtil.getAgentApiKey();
    }

    private static String resolveApiBase(String explicitApiBase) {
        if (StringUtils.isNotBlank(explicitApiBase)) {
            return explicitApiBase.trim();
        }
        String envApiBase = System.getenv(MODEL_AGENT_API_BASE);
        return StringUtils.isBlank(envApiBase) ? DEFAULT_API_BASE : envApiBase.trim();
    }

    private static String requireText(String value, String message) {
        String trimmed = StringUtils.trimToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(message);
        }
        return trimmed;
    }

    public static final class Builder {

        private String modelName;
        private String apiKey;
        private String apiBase;
        private String thinking;

        private Builder() {}

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder apiBase(String apiBase) {
            this.apiBase = apiBase;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            return apiBase(baseUrl);
        }

        public Builder thinking(String thinking) {
            this.thinking = thinking;
            return this;
        }

        public ArkLlmConfig build() {
            return new ArkLlmConfig(this);
        }
    }
}
