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
package com.volcengine.veadk.knowledgebase;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only adapter that exposes an existing Java knowledge-base service as a new backend. */
public final class KnowledgebaseServiceBackendAdapter implements KnowledgebaseBackend {

    private final String index;
    private final BaseKnowledgebaseService service;

    public KnowledgebaseServiceBackendAdapter(String index, BaseKnowledgebaseService service) {
        if (index == null || index.isBlank()) {
            throw new IllegalArgumentException("index must not be blank");
        }
        this.index = index;
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String index() {
        return index;
    }

    @Override
    public boolean add(List<KnowledgebaseEntry> entries) {
        throw new UnsupportedOperationException("The legacy knowledge-base service is read-only");
    }

    @Override
    public List<KnowledgebaseEntry> search(String query, int topK) {
        SearchKnowledgebaseResponse response = service.searchKnowledgebase(query).blockingGet();
        if (response == null || response.getKnowledgebaseEntries() == null) {
            return List.of();
        }
        return response.getKnowledgebaseEntries().stream()
                .limit(topK)
                .map(
                        entry ->
                                new KnowledgebaseEntry(
                                        entry.getContent(),
                                        entry.getMetadata() == null
                                                ? Map.of()
                                                : Map.copyOf(entry.getMetadata())))
                .toList();
    }
}
