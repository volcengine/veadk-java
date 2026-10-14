/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.identity;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves managed outbound credentials using an agent workload identity. */
public final class IdentityCredentials {
    private final IdentityClient client;
    private final WorkloadTokenManager tokens;

    public IdentityCredentials(IdentityClient client) {
        this.client = Objects.requireNonNull(client, "client must be set.");
        this.tokens = new WorkloadTokenManager(client);
    }

    /** API keys are cached in Session state with the same scope as veadk-python. */
    public String apiKey(
            Map<String, Object> state, String agentName, String userId, String providerName)
            throws Exception {
        String key = "api_key:" + agentName + ":" + userId + ":" + providerName;
        Object cached = state.get(key);
        if (cached instanceof String value && !value.isBlank()) return value;
        String value = client.getApiKey(providerName, tokens.get(state, agentName, userId));
        if (value == null || value.isBlank())
            throw new IllegalStateException("Identity response is missing ApiKey.");
        state.put(key, value);
        return value;
    }

    public OAuth2Credential oauth2(
            Map<String, Object> state,
            String agentName,
            String userId,
            String providerName,
            String flow,
            List<String> scopes,
            String redirectUrl,
            boolean forceAuthentication)
            throws Exception {
        return client.getOAuth2Credential(
                providerName,
                tokens.get(state, agentName, userId),
                flow,
                scopes,
                redirectUrl,
                forceAuthentication);
    }

    /** Workload bearer token for direct propagation to an outbound service. */
    public String workloadToken(Map<String, Object> state, String agentName, String userId)
            throws Exception {
        return tokens.get(state, agentName, userId);
    }
}
