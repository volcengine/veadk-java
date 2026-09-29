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

import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.MemoryEntry;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.sessions.Session;
import com.volcengine.veadk.integration.vikingmemory.Message;
import com.volcengine.veadk.integration.vikingmemory.Metadata;
import com.volcengine.veadk.integration.vikingmemory.VikingMemoryWrapper;
import com.volcengine.veadk.utils.EnvUtil;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VikingMemoryService implements BaseMemoryService {

    private static final Logger log = LoggerFactory.getLogger(VikingMemoryService.class);

    private VikingMemoryWrapper vikingMemoryWrapper;
    private String appName;
    private int topK = 5;
    private List<String> builtinEventTypes;

    public VikingMemoryService(String appName) {
        this(appName, null);
    }

    public VikingMemoryService(String appName, String apiKey) {
        if (null != appName && !appName.matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException(
                    "appName can only contain English letters, numbers, and underscores, and must"
                            + " start with an English letter.");
        }
        this.appName = appName;

        this.builtinEventTypes = List.of(EnvUtil.getVikingMmemoryType().split(","));

        String resolvedApiKey = EnvUtil.getVikingMemoryApiKey(apiKey);
        String accessKey = EnvUtil.getOptionalAccessKey();
        String secretKey = EnvUtil.getOptionalSecretKey();
        boolean hasManagementCredentials =
                StringUtils.isNotBlank(accessKey) && StringUtils.isNotBlank(secretKey);
        if (resolvedApiKey == null && !hasManagementCredentials) {
            throw new IllegalStateException(
                    "Viking Memory requires DATABASE_VIKINGMEM_API_KEY or both"
                            + " VOLCENGINE_ACCESS_KEY and VOLCENGINE_SECRET_KEY.");
        }

        vikingMemoryWrapper = new VikingMemoryWrapper(accessKey, secretKey, resolvedApiKey);
        if (hasManagementCredentials && !vikingMemoryWrapper.isCollectionExists(appName)) {
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
}
