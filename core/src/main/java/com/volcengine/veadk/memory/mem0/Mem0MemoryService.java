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
package com.volcengine.veadk.memory.mem0;

import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.MemoryEntry;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Mem0MemoryService implements BaseMemoryService {

    private static final Logger log = LoggerFactory.getLogger(Mem0MemoryService.class);
    private static final String DEFAULT_APP_NAME = "default_app";
    private static final String DEFAULT_USER_ID = "default_user";

    private final String appName;
    private final String defaultUserId;
    private final int topK;
    private final Mem0RuntimeClient runtimeClient;

    public Mem0MemoryService(String appName) {
        this(builder().appName(appName));
    }

    public Mem0MemoryService(String appName, Mem0Config config) {
        this(builder().appName(appName).config(config));
    }

    private Mem0MemoryService(Builder builder) {
        this.appName = normalizeAppName(builder.appName);
        this.defaultUserId = StringUtils.defaultIfBlank(builder.defaultUserId, DEFAULT_USER_ID);
        this.topK = builder.topK > 0 ? builder.topK : 5;

        if (builder.runtimeClient != null) {
            this.runtimeClient = builder.runtimeClient;
            return;
        }

        Mem0Config config = builder.config == null ? Mem0Config.fromEnv() : builder.config;
        Mem0AuthClient authClient =
                builder.authClient == null ? new Mem0AuthClient() : builder.authClient;
        ResolvedMem0Credential credential = authClient.resolve(config);
        this.runtimeClient = new Mem0RuntimeClient(credential.getBaseUrl(), credential.getApiKey());
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Completable addSessionToMemory(Session session) {
        return Completable.fromAction(
                () -> {
                    List<Mem0Message> messages = extractUserMessages(session.events());
                    if (messages.isEmpty()) {
                        return;
                    }
                    boolean success =
                            runtimeClient.addMemory(resolveUserId(session.userId()), messages);
                    if (!success) {
                        throw new IllegalStateException("Failed to add session to Mem0 memory.");
                    }
                });
    }

    @Override
    public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
        return Single.fromCallable(
                () -> {
                    try {
                        List<MemoryEntry> entries =
                                runtimeClient
                                        .searchMemory(resolveUserId(userId), query, topK)
                                        .stream()
                                        .map(this::toMemoryEntry)
                                        .collect(Collectors.toList());
                        return SearchMemoryResponse.builder().memories(entries).build();
                    } catch (Exception e) {
                        log.error("searchMemory failed", e);
                        return SearchMemoryResponse.builder()
                                .memories(Collections.emptyList())
                                .build();
                    }
                });
    }

    String getAppName() {
        return appName;
    }

    int getTopK() {
        return topK;
    }

    List<Mem0Message> extractUserMessages(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return Collections.emptyList();
        }

        List<Mem0Message> messages = new ArrayList<>();
        for (Event event : events) {
            if (!"user".equals(event.author()) || event.content().isEmpty()) {
                continue;
            }
            String text = extractText(event.content().get());
            if (StringUtils.isNotBlank(text)) {
                messages.add(new Mem0Message("user", text));
            }
        }
        return messages;
    }

    private String extractText(Content content) {
        Optional<List<Part>> parts = content.parts();
        if (parts.isEmpty()) {
            return null;
        }
        return parts.get().stream()
                .map(Part::text)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("\n"));
    }

    private String resolveUserId(String userId) {
        return StringUtils.defaultIfBlank(userId, defaultUserId);
    }

    private MemoryEntry toMemoryEntry(Mem0MemoryResult result) {
        return MemoryEntry.builder()
                .author("user")
                .content(
                        Content.builder()
                                .role("user")
                                .parts(
                                        Collections.singletonList(
                                                Part.builder().text(result.getMemory()).build()))
                                .build())
                .build();
    }

    private String normalizeAppName(String appName) {
        String normalized = StringUtils.defaultIfBlank(appName, DEFAULT_APP_NAME);
        if (!normalized.matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException(
                    "appName can only contain English letters, numbers, and underscores, and must"
                            + " start with an English letter.");
        }
        return normalized;
    }

    public static class Builder {
        private String appName;
        private String defaultUserId;
        private int topK = 5;
        private Mem0Config config;
        private Mem0AuthClient authClient;
        private Mem0RuntimeClient runtimeClient;

        public Builder appName(String appName) {
            this.appName = appName;
            return this;
        }

        public Builder defaultUserId(String defaultUserId) {
            this.defaultUserId = defaultUserId;
            return this;
        }

        public Builder userId(String userId) {
            this.defaultUserId = userId;
            return this;
        }

        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        public Builder config(Mem0Config config) {
            this.config = config;
            return this;
        }

        Builder authClient(Mem0AuthClient authClient) {
            this.authClient = authClient;
            return this;
        }

        Builder runtimeClient(Mem0RuntimeClient runtimeClient) {
            this.runtimeClient = runtimeClient;
            return this;
        }

        public Mem0MemoryService build() {
            return new Mem0MemoryService(this);
        }
    }
}
