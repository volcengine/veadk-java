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
package com.volcengine.veadk.memory.viking;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.MemoryEntry;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.sessions.Session;
import com.volcengine.veadk.integration.vikingmemory.Message;
import com.volcengine.veadk.integration.vikingmemory.Metadata;
import com.volcengine.veadk.integration.vikingmemory.VikingMemoryWrapper;
import com.volcengine.veadk.memory.LongTermMemoryBackend;
import com.volcengine.veadk.utils.EnvUtil;
import com.volcengine.veadk.utils.JSONUtil;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VikingMemoryService implements BaseMemoryService, LongTermMemoryBackend {

    private static final Logger log = LoggerFactory.getLogger(VikingMemoryService.class);

    private VikingMemoryWrapper vikingMemoryWrapper;
    private String appName;
    private int topK = 5;
    private List<String> builtinEventTypes;

    public VikingMemoryService(String appName) {
        if (null != appName && !appName.matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException(
                    "appName can only contain English letters, numbers, and underscores, and must"
                            + " start with an English letter.");
        }
        this.appName = appName;

        this.builtinEventTypes = List.of(EnvUtil.getVikingMmemoryType().split(","));

        vikingMemoryWrapper =
                new VikingMemoryWrapper(EnvUtil.getAccessKey(), EnvUtil.getSecretKey());
        if (!vikingMemoryWrapper.isCollectionExists(appName)) {
            vikingMemoryWrapper.createCollection(appName, this.builtinEventTypes);
        }
    }

    @Override
    public Completable addSessionToMemory(Session session) {
        return Completable.fromAction(
                () -> {
                    List<Message> messages =
                            session.events().stream()
                                    .filter(
                                            event -> {
                                                return "user".equals(event.author())
                                                        && event.content().isPresent()
                                                        && event.content().get().parts().isPresent()
                                                        && event.content()
                                                                .get()
                                                                .parts()
                                                                .get()
                                                                .get(0)
                                                                .text()
                                                                .isPresent();
                                            })
                                    .map(
                                            event -> {
                                                String content =
                                                        event.content()
                                                                .get()
                                                                .parts()
                                                                .get()
                                                                .get(0)
                                                                .text()
                                                                .get();
                                                return new Message("user", content);
                                            })
                                    .collect(Collectors.toList());

                    if (messages.isEmpty()) {
                        return;
                    }

                    try {
                        Metadata metadata =
                                new Metadata(
                                        session.userId(), "assistant", System.currentTimeMillis());
                        vikingMemoryWrapper.addSession(session.appName(), messages, metadata);
                    } catch (Exception e) {
                        log.error("addSessionToMemory failed", e);
                        throw new RuntimeException(e);
                    }
                });
    }

    @Override
    public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
        return Single.fromCallable(
                () -> {
                    List<MemoryEntry> memoryEntries =
                            vikingMemoryWrapper.searchMemory(
                                    appName, userId, query, topK, this.builtinEventTypes);
                    return SearchMemoryResponse.builder().setMemories(memoryEntries).build();
                });
    }

    @Override
    public String index() {
        return appName;
    }

    @Override
    public boolean saveMemory(
            String userId, List<String> eventStrings, Map<String, Object> options) {
        List<Message> messages =
                eventStrings.stream()
                        .map(VikingMemoryService::toMessage)
                        .collect(Collectors.toList());
        if (messages.isEmpty()) {
            return true;
        }
        try {
            Metadata metadata = new Metadata(userId, "assistant", System.currentTimeMillis());
            return vikingMemoryWrapper.addSession(appName, messages, metadata);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to save Viking long-term memory", exception);
        }
    }

    @Override
    public List<String> searchMemory(
            String userId, String query, int requestedTopK, Map<String, Object> options) {
        try {
            return vikingMemoryWrapper
                    .searchMemory(appName, userId, query, requestedTopK, builtinEventTypes)
                    .stream()
                    .map(JSONUtil::toJson)
                    .toList();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to search Viking long-term memory", exception);
        }
    }

    private static Message toMessage(String eventString) {
        try {
            JsonNode event = JSONUtil.parseJson(eventString);
            JsonNode content = event.path("content");
            String role = content.path("role").asText(event.path("role").asText("user"));
            JsonNode parts = content.has("parts") ? content.path("parts") : event.path("parts");
            List<String> textParts = new ArrayList<>();
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    String text = part.isTextual() ? part.asText() : part.path("text").asText("");
                    if (!text.isBlank()) {
                        textParts.add(text);
                    }
                }
            }
            String text = String.join("\n", textParts);
            if (!text.isBlank()) {
                return new Message(normalizeRole(role), text);
            }
        } catch (IOException exception) {
            // Plain text is a valid backend input and is handled below.
        }
        return new Message("user", eventString);
    }

    private static String normalizeRole(String role) {
        return switch (role) {
            case "assistant", "model" -> "assistant";
            case "system" -> "system";
            default -> "user";
        };
    }
}
