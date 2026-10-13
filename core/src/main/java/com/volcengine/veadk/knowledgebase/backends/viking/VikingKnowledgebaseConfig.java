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
package com.volcengine.veadk.knowledgebase.backends.viking;

import com.volcengine.veadk.utils.EnvUtil;

public class VikingKnowledgebaseConfig {

    public static final boolean DEFAULT_RERANK = true;
    public static final int DEFAULT_CHUNK_DIFFUSION_COUNT = 3;

    private final String accessKey;
    private final String secretKey;
    private final String apiKey;
    private final boolean rerank;
    private final int chunkDiffusionCount;

    public VikingKnowledgebaseConfig(
            String accessKey, String secretKey, boolean rerank, int chunkDiffusionCount) {
        this(accessKey, secretKey, null, rerank, chunkDiffusionCount);
    }

    public VikingKnowledgebaseConfig(
            String accessKey,
            String secretKey,
            String apiKey,
            boolean rerank,
            int chunkDiffusionCount) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.apiKey = EnvUtil.sanitizeApiKey(apiKey);
        this.rerank = rerank;
        this.chunkDiffusionCount = chunkDiffusionCount;
    }

    public static VikingKnowledgebaseConfig fromEnv() {
        return fromEnv(null);
    }

    public static VikingKnowledgebaseConfig fromEnv(String explicitApiKey) {
        return new VikingKnowledgebaseConfig(
                EnvUtil.getOptionalAccessKey(),
                EnvUtil.getOptionalSecretKey(),
                EnvUtil.resolveApiKey(explicitApiKey, EnvUtil.getVikingKnowledgebaseApiKey()),
                DEFAULT_RERANK,
                DEFAULT_CHUNK_DIFFUSION_COUNT);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getApiKey() {
        return apiKey;
    }

    public boolean isRerank() {
        return rerank;
    }

    public int getChunkDiffusionCount() {
        return chunkDiffusionCount;
    }

    public boolean hasApiKey() {
        return apiKey != null;
    }

    public boolean hasManagementCredentials() {
        return accessKey != null && secretKey != null;
    }

    public static class Builder {
        private String accessKey;
        private String secretKey;
        private String apiKey;
        private boolean rerank = DEFAULT_RERANK;
        private int chunkDiffusionCount = DEFAULT_CHUNK_DIFFUSION_COUNT;

        public Builder accessKey(String accessKey) {
            this.accessKey = accessKey;
            return this;
        }

        public Builder secretKey(String secretKey) {
            this.secretKey = secretKey;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder rerank(boolean rerank) {
            this.rerank = rerank;
            return this;
        }

        public Builder chunkDiffusionCount(int chunkDiffusionCount) {
            this.chunkDiffusionCount = chunkDiffusionCount;
            return this;
        }

        public VikingKnowledgebaseConfig build() {
            return new VikingKnowledgebaseConfig(
                    accessKey, secretKey, apiKey, rerank, chunkDiffusionCount);
        }
    }
}
