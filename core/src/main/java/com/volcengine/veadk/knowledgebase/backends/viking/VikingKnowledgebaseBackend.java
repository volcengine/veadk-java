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

import com.volcengine.veadk.integration.vikingknowledgebase.VikingKnowledgebaseApiKeyClient;
import com.volcengine.veadk.integration.vikingknowledgebase.VikingKnowledgebaseWrapper;
import com.volcengine.veadk.knowledgebase.KnowledgebaseEntry;
import com.volcengine.veadk.knowledgebase.backends.BaseKnowledgebaseBackend;
import com.volcengine.veadk.utils.EnvUtil;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

public class VikingKnowledgebaseBackend implements BaseKnowledgebaseBackend {

    private final String collectionName;
    private final VikingKnowledgebaseWrapper managementClient;
    private final VikingKnowledgebaseWrapper signedDataClient;
    private final VikingKnowledgebaseApiKeyClient apiKeyDataClient;
    private final boolean rerank;
    private final int chunkDiffusionCount;

    public VikingKnowledgebaseBackend(String collectionName) {
        this(validateCollectionName(collectionName), defaultConfig());
    }

    private static VikingKnowledgebaseConfig defaultConfig() {
        if (StringUtils.isNotBlank(System.getenv("DATABASE_VIKING_API_KEY"))) {
            return VikingKnowledgebaseConfig.fromEnv();
        }
        return VikingKnowledgebaseConfig.builder()
                .accessKey(EnvUtil.getAccessKey())
                .secretKey(EnvUtil.getSecretKey())
                .build();
    }

    public VikingKnowledgebaseBackend(String collectionName, VikingKnowledgebaseConfig config) {
        this.collectionName = validateCollectionName(collectionName);
        this.rerank = config.isRerank();
        this.chunkDiffusionCount = config.getChunkDiffusionCount();
        this.apiKeyDataClient =
                config.hasApiKey()
                        ? new VikingKnowledgebaseApiKeyClient(
                                config.getApiKey(),
                                config.getBaseUrl(),
                                config.getProject(),
                                config.getResourceId())
                        : null;
        if (!config.hasApiKey() && !config.hasManagementCredentials()) {
            throw new IllegalStateException(
                    "Viking management credentials are required when API Key is not configured.");
        }
        this.managementClient =
                config.hasManagementCredentials()
                        ? new VikingKnowledgebaseWrapper(
                                config.getAccessKey(),
                                config.getSecretKey(),
                                config.getSessionToken())
                        : null;
        this.signedDataClient = config.hasApiKey() ? null : managementClient;
        if (managementClient != null) ensureCollection();
    }

    VikingKnowledgebaseBackend(
            String collectionName,
            VikingKnowledgebaseWrapper wrapper,
            boolean rerank,
            int chunkDiffusionCount) {
        this.collectionName = collectionName;
        this.managementClient = wrapper;
        this.signedDataClient = wrapper;
        this.apiKeyDataClient = null;
        this.rerank = rerank;
        this.chunkDiffusionCount = chunkDiffusionCount;
        precheckIndexNaming();
        ensureCollection();
    }

    @Override
    public void precheckIndexNaming() {
        validateCollectionName(collectionName);
    }

    @Override
    public boolean addDoc(String tosUrl) {
        if (managementClient == null) {
            throw new IllegalStateException(
                    "Viking management credentials are required for document management.");
        }
        return managementClient.addDoc(collectionName, tosUrl);
    }

    @Override
    public List<KnowledgebaseEntry> search(String query, int topK) throws IOException {
        if (StringUtils.isBlank(query)) {
            return List.of();
        }
        List<com.volcengine.veadk.integration.vikingknowledgebase.KnowledgebaseEntry> results =
                apiKeyDataClient != null
                        ? apiKeyDataClient.searchKnowledge(
                                collectionName, query, topK, null, rerank, chunkDiffusionCount)
                        : signedDataClient.searchKnowledge(
                                collectionName, query, topK, null, rerank, chunkDiffusionCount);
        return results.stream().map(VikingKnowledgebaseBackend::toKnowledgebaseEntry).toList();
    }

    private void ensureCollection() {
        if (!managementClient.isCollectionExists(collectionName)) {
            managementClient.createCollection(collectionName);
        }
    }

    private static KnowledgebaseEntry toKnowledgebaseEntry(
            com.volcengine.veadk.integration.vikingknowledgebase.KnowledgebaseEntry entry) {
        Map<String, String> metadata = entry.getMetadata() == null ? Map.of() : entry.getMetadata();
        return new KnowledgebaseEntry(entry.getContent(), metadata);
    }

    private static String validateCollectionName(String collectionName) {
        if (!(StringUtils.isNotBlank(collectionName)
                && collectionName.matches("^[a-zA-Z][a-zA-Z0-9_]*$"))) {
            throw new IllegalArgumentException(
                    "collectionName can only contain English letters, numbers, and underscores, and"
                            + " must start with an English letter.");
        }
        return collectionName;
    }
}
