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

import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.SearchMemoryResponse;
import com.volcengine.veadk.utils.JSONUtil;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only adapter that exposes an existing ADK memory service as a new backend. */
public final class MemoryServiceBackendAdapter implements LongTermMemoryBackend {

    private final String index;
    private final BaseMemoryService service;

    public MemoryServiceBackendAdapter(String index, BaseMemoryService service) {
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
    public boolean saveMemory(
            String userId, List<String> eventStrings, Map<String, Object> options) {
        throw new UnsupportedOperationException("The legacy memory service is read-only");
    }

    @Override
    public List<String> searchMemory(
            String userId, String query, int topK, Map<String, Object> options) {
        Object configuredAppName = options.get("appName");
        String appName = configuredAppName == null ? index : configuredAppName.toString();
        SearchMemoryResponse response = service.searchMemory(appName, userId, query).blockingGet();
        if (response == null || response.memories() == null) {
            return List.of();
        }
        return response.memories().stream().limit(topK).map(JSONUtil::toJson).toList();
    }
}
