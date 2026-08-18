/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.volcengine.veadk.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Deterministic local backend suitable for development and tests. */
public final class InMemoryLongTermMemoryBackend implements LongTermMemoryBackend {

    private final String index;
    private final Map<String, CopyOnWriteArrayList<String>> memoriesByUser =
            new ConcurrentHashMap<>();

    public InMemoryLongTermMemoryBackend(String index) {
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
    public boolean saveMemory(
            String userId, List<String> eventStrings, Map<String, Object> options) {
        String normalizedUserId = requireText(userId, "userId");
        List<String> events = List.copyOf(eventStrings);
        if (events.stream().anyMatch(event -> event == null || event.isBlank())) {
            throw new IllegalArgumentException("eventStrings must not contain blank values");
        }
        memoriesByUser
                .computeIfAbsent(normalizedUserId, ignored -> new CopyOnWriteArrayList<>())
                .addAll(events);
        return true;
    }

    @Override
    public List<String> searchMemory(
            String userId, String query, int topK, Map<String, Object> options) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        requireText(query, "query");
        List<String> memories =
                memoriesByUser.getOrDefault(
                        requireText(userId, "userId"), new CopyOnWriteArrayList<>());
        Set<String> queryTerms = terms(query);
        List<RankedMemory> ranked = new ArrayList<>(memories.size());
        for (int position = 0; position < memories.size(); position++) {
            String memory = memories.get(position);
            Set<String> memoryTerms = terms(memory);
            long overlap = queryTerms.stream().filter(memoryTerms::contains).count();
            if (memory.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                overlap++;
            }
            ranked.add(new RankedMemory(memory, overlap, position));
        }
        ranked.sort(
                Comparator.comparingLong(RankedMemory::score)
                        .reversed()
                        .thenComparingInt(RankedMemory::insertionOrder));
        return ranked.stream().limit(topK).map(RankedMemory::value).toList();
    }

    public int size(String userId) {
        return memoriesByUser.getOrDefault(userId, new CopyOnWriteArrayList<>()).size();
    }

    private static Set<String> terms(String text) {
        Set<String> terms = new HashSet<>();
        for (String term : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (!term.isBlank()) {
                terms.add(term);
            }
        }
        return terms;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private record RankedMemory(String value, long score, int insertionOrder) {}
}
