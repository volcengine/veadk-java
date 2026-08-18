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

import java.util.List;
import java.util.Map;

/** Storage and retrieval contract implemented by long-term-memory backends. */
public interface LongTermMemoryBackend extends AutoCloseable {

    String index();

    boolean saveMemory(String userId, List<String> eventStrings, Map<String, Object> options);

    List<String> searchMemory(String userId, String query, int topK, Map<String, Object> options);

    default boolean saveMemory(String userId, List<String> eventStrings) {
        return saveMemory(userId, eventStrings, Map.of());
    }

    default List<String> searchMemory(String userId, String query, int topK) {
        return searchMemory(userId, query, topK, Map.of());
    }

    default String backendName() {
        return getClass().getSimpleName();
    }

    @Override
    default void close() {}
}
