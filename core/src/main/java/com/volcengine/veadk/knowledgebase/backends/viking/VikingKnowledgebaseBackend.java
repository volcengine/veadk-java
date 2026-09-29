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

import com.volcengine.veadk.integration.vikingknowledgebase.VikingKnowledgebaseWrapper;
import com.volcengine.veadk.knowledgebase.KnowledgebaseEntry;
import com.volcengine.veadk.knowledgebase.backends.BaseKnowledgebaseBackend;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

public class VikingKnowledgebaseBackend implements BaseKnowledgebaseBackend {

    private final String collectionName;
    private final VikingKnowledgebaseWrapper wrapper;
    private final boolean rerank;
    private final int chunkDiffusionCount;

    public VikingKnowledgebaseBackend(String collectionName) {
        this(validateCollectionName(collectionName), VikingKnowledgebaseConfig.fromEnv());
    }

    public VikingKnowledgebaseBackend(String collectionName, VikingKnowledgebaseConfig config) {
        this(
                validateCollectionName(collectionName),
                new VikingKnowledgebaseWrapper(
                        config.getAccessKey(), config.getSecretKey(), config.getApiKey()),
                config.isRerank(),
                config.getChunkDiffusionCount(),
                config.hasManagementCredentials());
    }

    VikingKnowledgebaseBackend(
            String collectionName,
            VikingKnowledgebaseWrapper wrapper,
            boolean rerank,
            int chunkDiffusionCount) {
        this(collectionName, wrapper, rerank, chunkDiffusionCount, true);
    }

    VikingKnowledgebaseBackend(
            String collectionName,
            VikingKnowledgebaseWrapper wrapper,
            boolean rerank,
            int chunkDiffusionCount,
            boolean hasManagementCredentials) {
        this.collectionName = collectionName;
        this.wrapper = wrapper;
        this.rerank = rerank;
        this.chunkDiffusionCount = chunkDiffusionCount;
        precheckIndexNaming();
        if (hasManagementCredentials) {
            ensureCollection();
        }
    }

    @Override
    public void precheckIndexNaming() {
        validateCollectionName(collectionName);
    }

    @Override
    public boolean addDoc(String tosUrl) {
        return wrapper.addDoc(collectionName, tosUrl);
    }

    @Override
    public List<KnowledgebaseEntry> search(String query, int topK) throws IOException {
        if (StringUtils.isBlank(query)) {
            return List.of();
        }
        return wrapper
                .searchKnowledge(collectionName, query, topK, null, rerank, chunkDiffusionCount)
                .stream()
                .map(VikingKnowledgebaseBackend::toKnowledgebaseEntry)
                .toList();
    }

    private void ensureCollection() {
        if (!wrapper.isCollectionExists(collectionName)) {
            wrapper.createCollection(collectionName);
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
