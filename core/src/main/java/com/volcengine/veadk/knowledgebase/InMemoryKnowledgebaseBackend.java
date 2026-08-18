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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Deterministic in-memory backend suitable for local development and tests. */
public final class InMemoryKnowledgebaseBackend implements KnowledgebaseBackend {

    private final String index;
    private final CopyOnWriteArrayList<KnowledgebaseEntry> entries = new CopyOnWriteArrayList<>();

    public InMemoryKnowledgebaseBackend(String index) {
        if (index == null || index.isBlank()) {
            throw new IllegalArgumentException("index must not be blank");
        }
        this.index = index;
    }

    @Override
    public String index() {
        return index;
    }

    @Override
    public boolean add(List<KnowledgebaseEntry> entries) {
        this.entries.addAll(List.copyOf(entries));
        return true;
    }

    @Override
    public List<KnowledgebaseEntry> search(String query, int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        Set<String> queryTerms = terms(query);
        List<RankedEntry> ranked = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            KnowledgebaseEntry entry = entries.get(index);
            Set<String> contentTerms = terms(entry.content());
            long overlap = queryTerms.stream().filter(contentTerms::contains).count();
            ranked.add(new RankedEntry(entry, overlap, index));
        }
        ranked.sort(
                Comparator.comparingLong(RankedEntry::score)
                        .reversed()
                        .thenComparingInt(RankedEntry::insertionOrder));
        return ranked.stream().limit(topK).map(RankedEntry::entry).toList();
    }

    private static Set<String> terms(String text) {
        Set<String> terms = new HashSet<>();
        if (text == null) {
            return terms;
        }
        for (String term : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (!term.isBlank()) {
                terms.add(term);
            }
        }
        return terms;
    }

    private record RankedEntry(KnowledgebaseEntry entry, long score, int insertionOrder) {}
}
