/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates. */
package com.volcengine.veadk.memory.viking;

import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.MemoryEntry;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.sessions.Session;
import com.volcengine.veadk.integration.vikingmemory.Message;
import com.volcengine.veadk.integration.vikingmemory.Metadata;
import com.volcengine.veadk.integration.vikingmemory.VikingMemoryApiKeyClient;
import com.volcengine.veadk.integration.vikingmemory.VikingMemoryWrapper;
import com.volcengine.veadk.utils.EnvUtil;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

public class VikingMemoryService implements BaseMemoryService {
    private final VikingMemoryWrapper managementClient;
    private final VikingMemoryWrapper signedDataClient;
    private final VikingMemoryApiKeyClient apiKeyDataClient;
    private final int topK = 5;
    private final List<String> builtinEventTypes;
    private final LongSupplier clock;

    public VikingMemoryService(String appName) {
        this(validateAndReturn(appName), defaultConfig());
    }

    private static String validateAndReturn(String appName) {
        validateCollectionName(appName);
        return appName;
    }

    private static VikingMemoryConfig defaultConfig() {
        if (StringUtils.isNotBlank(System.getenv("DATABASE_VIKINGMEM_API_KEY"))) {
            return VikingMemoryConfig.fromEnv();
        }
        return VikingMemoryConfig.builder()
                .accessKey(EnvUtil.getAccessKey())
                .secretKey(EnvUtil.getSecretKey())
                .memoryTypes(EnvUtil.getVikingMmemoryType())
                .build();
    }

    public VikingMemoryService(String appName, VikingMemoryConfig config) {
        this(appName, config, System::currentTimeMillis);
    }

    VikingMemoryService(String appName, VikingMemoryConfig config, LongSupplier clock) {
        validateCollectionName(appName);
        this.clock = clock;
        this.builtinEventTypes = config.getMemoryTypes();
        this.apiKeyDataClient =
                config.hasApiKey()
                        ? new VikingMemoryApiKeyClient(
                                config.getApiKey(), config.getBaseUrl(), config.getProject())
                        : null;
        if (!config.hasApiKey() && !config.hasManagementCredentials())
            throw new IllegalStateException(
                    "Viking management credentials are required when API Key is not configured.");
        this.managementClient =
                config.hasManagementCredentials()
                        ? new VikingMemoryWrapper(
                                config.getAccessKey(),
                                config.getSecretKey(),
                                config.getSessionToken())
                        : null;
        this.signedDataClient = config.hasApiKey() ? null : managementClient;
        if (managementClient != null && !managementClient.isCollectionExists(appName))
            managementClient.createCollection(appName, builtinEventTypes);
    }

    @Override
    public Completable addSessionToMemory(Session session) {
        return Completable.fromAction(
                () -> {
                    List<Message> messages =
                            session.events().stream()
                                    .filter(
                                            event ->
                                                    "user".equals(event.author())
                                                            && event.content().isPresent()
                                                            && event.content()
                                                                    .get()
                                                                    .parts()
                                                                    .isPresent()
                                                            && !event.content()
                                                                    .get()
                                                                    .parts()
                                                                    .get()
                                                                    .isEmpty()
                                                            && event.content()
                                                                    .get()
                                                                    .parts()
                                                                    .get()
                                                                    .get(0)
                                                                    .text()
                                                                    .isPresent())
                                    .map(
                                            event ->
                                                    new Message(
                                                            "user",
                                                            event.content()
                                                                    .get()
                                                                    .parts()
                                                                    .get()
                                                                    .get(0)
                                                                    .text()
                                                                    .get()))
                                    .collect(Collectors.toList());
                    if (messages.isEmpty()) return;
                    if (apiKeyDataClient == null) {
                        signedDataClient.addSession(
                                session.appName(),
                                messages,
                                new Metadata(
                                        session.userId(), "assistant", System.currentTimeMillis()));
                        return;
                    }
                    validateCollectionName(session.appName());
                    if (StringUtils.isBlank(session.userId()))
                        throw new IllegalArgumentException("session userId must not be blank.");
                    long now = clock.getAsLong();
                    if (now <= 0)
                        throw new IllegalStateException(
                                "Viking Memory request time must be positive.");
                    apiKeyDataClient.addSession(
                            session.appName(),
                            normalizeSessionId(session.id()),
                            messages,
                            new Metadata(session.userId(), "assistant", now));
                });
    }

    @Override
    public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
        return Single.fromCallable(
                () -> {
                    List<MemoryEntry> entries =
                            apiKeyDataClient != null
                                    ? apiKeyDataClient.searchMemory(
                                            appName, userId, query, topK, builtinEventTypes)
                                    : signedDataClient.searchMemory(
                                            appName, userId, query, topK, builtinEventTypes);
                    return SearchMemoryResponse.builder().memories(entries).build();
                });
    }

    static String normalizeSessionId(String sessionId) {
        if (StringUtils.isBlank(sessionId)) return null;
        if (sessionId.matches("^[A-Za-z][A-Za-z0-9_]{0,127}$")) return sessionId;
        try {
            return "s_"
                    + HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(sessionId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private static void validateCollectionName(String name) {
        if (!(StringUtils.isNotBlank(name) && name.matches("^[a-zA-Z][a-zA-Z0-9_]*$")))
            throw new IllegalArgumentException(
                    "appName can only contain English letters, numbers, and underscores, and must"
                            + " start with an English letter.");
    }
}
