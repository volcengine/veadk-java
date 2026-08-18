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

/** Storage and retrieval contract implemented by all knowledge-base backends. */
public interface KnowledgebaseBackend extends AutoCloseable {

    String index();

    boolean add(List<KnowledgebaseEntry> entries);

    List<KnowledgebaseEntry> search(String query, int topK);

    default String backendName() {
        return getClass().getSimpleName();
    }

    @Override
    default void close() {}
}
