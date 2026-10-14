/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Session-scoped workload token cache compatible with veadk-python. */
public final class WorkloadTokenManager {
    public static final Duration DEFAULT_REFRESH_BUFFER = Duration.ofSeconds(60);
    private final IdentityClient client;
    private final Clock clock;
    private final Duration refreshBuffer;

    public WorkloadTokenManager(IdentityClient client) {
        this(client, Clock.systemUTC(), DEFAULT_REFRESH_BUFFER);
    }

    public WorkloadTokenManager(IdentityClient client, Clock clock, Duration refreshBuffer) {
        this.client = Objects.requireNonNull(client, "client must be set.");
        this.clock = Objects.requireNonNull(clock, "clock must be set.");
        this.refreshBuffer = Objects.requireNonNull(refreshBuffer, "refreshBuffer must be set.");
    }

    public String get(Map<String, Object> state, String agentName, String userId) throws Exception {
        Objects.requireNonNull(state, "state must be set.");
        String key = "workload_token:" + agentName + ":" + userId;
        Object cached = state.get(key);
        if (cached instanceof WorkloadToken token
                && clock.instant().isBefore(token.expiresAt().minus(refreshBuffer))) {
            return token.value();
        }
        WorkloadToken token = client.getWorkloadToken(agentName, userId, null);
        state.put(key, token);
        return token.value();
    }
}
