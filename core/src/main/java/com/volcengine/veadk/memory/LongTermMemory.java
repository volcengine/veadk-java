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

import com.fasterxml.jackson.databind.JsonNode;
import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.MemoryEntry;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.volcengine.veadk.utils.JSONUtil;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Backend-neutral long-term-memory facade aligned with the Python VeADK contract. */
public final class LongTermMemory implements BaseMemoryService, AutoCloseable {

    public static final String DEFAULT_INDEX = "default_app";
    public static final int DEFAULT_TOP_K = 5;

    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);

    private final LongTermMemoryBackend backend;
    private final String appName;
    private final int topK;

    public LongTermMemory() {
        this(DEFAULT_INDEX);
    }

    public LongTermMemory(String index) {
        this(new InMemoryLongTermMemoryBackend(index), index, DEFAULT_TOP_K);
    }

    public LongTermMemory(LongTermMemoryBackend backend) {
        this(backend, backend.index(), DEFAULT_TOP_K);
    }

    public LongTermMemory(LongTermMemoryBackend backend, int topK) {
        this(backend, backend.index(), topK);
    }

    public LongTermMemory(LongTermMemoryBackend backend, String appName, int topK) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.appName = requireText(appName, "appName");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        this.topK = topK;
    }

    public String index() {
        return backend.index();
    }

    public String appName() {
        return appName;
    }

    public String backend() {
        return backend.backendName();
    }

    public int topK() {
        return topK;
    }

    @Override
    public Completable addSessionToMemory(Session session) {
        Objects.requireNonNull(session, "session");
        return Completable.fromAction(
                () -> {
                    List<String> eventStrings = serializeUserEvents(session.events());
                    if (eventStrings.isEmpty()) {
                        return;
                    }
                    Map<String, Object> options = new LinkedHashMap<>();
                    options.put(
                            "appName",
                            session.appName() == null || session.appName().isBlank()
                                    ? appName
                                    : session.appName());
                    options.put("sessionId", session.id());
                    backend.saveMemory(session.userId(), eventStrings, Map.copyOf(options));
                });
    }

    @Override
    public Single<SearchMemoryResponse> searchMemory(
            String requestedAppName, String userId, String query) {
        return Single.fromCallable(
                () -> {
                    List<String> chunks;
                    try {
                        chunks =
                                backend.searchMemory(
                                        userId,
                                        query,
                                        topK,
                                        Map.of(
                                                "appName",
                                                requestedAppName == null
                                                                || requestedAppName.isBlank()
                                                        ? appName
                                                        : requestedAppName));
                    } catch (Exception exception) {
                        log.warn(
                                "Long-term memory search failed; returning no memories", exception);
                        chunks = List.of();
                    }
                    List<MemoryEntry> entries = new ArrayList<>();
                    chunks.forEach(chunk -> entries.addAll(toMemoryEntries(chunk)));
                    return SearchMemoryResponse.builder().setMemories(entries).build();
                });
    }

    @Override
    public void close() {
        backend.close();
    }

    private static List<String> serializeUserEvents(List<Event> events) {
        List<String> serialized = new ArrayList<>();
        for (Event event : events) {
            if (!"user".equals(event.author()) || event.content().isEmpty()) {
                continue;
            }
            List<Part> parts = event.content().get().parts().orElse(List.of());
            List<Map<String, String>> textParts =
                    parts.stream()
                            .filter(part -> part.text().isPresent())
                            .map(part -> Map.of("text", part.text().get()))
                            .toList();
            if (textParts.isEmpty()) {
                continue;
            }
            String value = JSONUtil.toJson(Map.of("role", "user", "parts", textParts));
            if (!value.isBlank()) {
                serialized.add(value);
            }
        }
        return serialized;
    }

    private static List<MemoryEntry> toMemoryEntries(String chunk) {
        try {
            return toMemoryEntries(JSONUtil.parseJson(chunk));
        } catch (IOException exception) {
            return List.of(memoryEntry("user", chunk));
        }
    }

    private static List<MemoryEntry> toMemoryEntries(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return List.of(memoryEntry("user", node.asText()));
        }
        if (node.isArray()) {
            List<MemoryEntry> entries = new ArrayList<>();
            node.forEach(item -> entries.addAll(toMemoryEntries(item)));
            return entries;
        }

        JsonNode memories = node.get("memories");
        if (memories != null && memories.isArray()) {
            return toMemoryEntries(memories);
        }

        JsonNode content = node.path("content");
        String role = firstText(content.path("role"), node.path("role"), "user");
        JsonNode parts = content.has("parts") ? content.path("parts") : node.path("parts");
        List<String> textParts = new ArrayList<>();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                String text = part.isTextual() ? part.asText() : part.path("text").asText("");
                if (!text.isBlank()) {
                    textParts.add(text);
                }
            }
        }
        if (textParts.isEmpty()) {
            for (String field : List.of("text", "abstract", "summary")) {
                String text = node.path(field).asText("");
                if (!text.isBlank()) {
                    textParts.add(text);
                    break;
                }
            }
        }
        if (textParts.isEmpty() && content.isTextual()) {
            textParts.add(content.asText());
        }
        return textParts.isEmpty()
                ? List.of()
                : List.of(memoryEntry(role, String.join("\n", textParts)));
    }

    private static String firstText(JsonNode first, JsonNode second, String fallback) {
        if (first != null && first.isTextual() && !first.asText().isBlank()) {
            return first.asText();
        }
        if (second != null && second.isTextual() && !second.asText().isBlank()) {
            return second.asText();
        }
        return fallback;
    }

    private static MemoryEntry memoryEntry(String role, String text) {
        return MemoryEntry.builder()
                .author(role)
                .content(Content.builder().role(role).parts(List.of(Part.fromText(text))).build())
                .build();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
