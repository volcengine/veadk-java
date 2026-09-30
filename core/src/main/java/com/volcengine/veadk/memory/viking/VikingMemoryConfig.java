/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates. */
package com.volcengine.veadk.memory.viking;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

public final class VikingMemoryConfig {
    private final String apiKey,
            project,
            region,
            baseUrl,
            cloudProvider,
            accessKey,
            secretKey,
            sessionToken;
    private final List<String> memoryTypes;

    private VikingMemoryConfig(Builder b) {
        cloudProvider =
                resolve(
                        b.cloudProvider,
                        "AGENTKIT_CLOUD_PROVIDER",
                        resolve(null, "CLOUD_PROVIDER", "volcengine"));
        String configuredRegion =
                resolve(b.region, "DATABASE_VIKING_REGION", resolve(null, "REGION", "cn-beijing"));
        region = "byteplus".equalsIgnoreCase(cloudProvider) ? "cn-hongkong" : configuredRegion;
        apiKey = resolve(b.apiKey, "DATABASE_VIKINGMEM_API_KEY", null);
        project = resolve(b.project, "DATABASE_VIKINGMEM_PROJECT", "default");
        String types =
                resolve(
                        b.memoryTypes,
                        "DATABASE_VIKINGMEM_MEMORY_TYPE",
                        "sys_event_v1,sys_profile_v1");
        memoryTypes =
                Arrays.stream(types.split(","))
                        .map(VikingMemoryConfig::normalize)
                        .filter(v -> v != null)
                        .toList();
        accessKey =
                normalize(b.accessKey) == null
                        ? cloudEnvironment("ACCESS_KEY")
                        : normalize(b.accessKey);
        secretKey =
                normalize(b.secretKey) == null
                        ? cloudEnvironment("SECRET_KEY")
                        : normalize(b.secretKey);
        sessionToken =
                normalize(b.sessionToken) == null
                        ? cloudEnvironment("SESSION_TOKEN")
                        : normalize(b.sessionToken);
        String endpoint =
                "https://api-knowledgebase.mlp."
                        + region
                        + ("byteplus".equalsIgnoreCase(cloudProvider)
                                ? ".bytepluses.com"
                                : ".volces.com");
        baseUrl = validate(resolve(b.baseUrl, "DATABASE_VIKINGMEM_BASE_URL", endpoint));
        if (project == null || memoryTypes.isEmpty())
            throw new IllegalArgumentException(
                    "Viking Memory project and memoryTypes must not be empty.");
    }

    private static String validate(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Viking Memory baseUrl must be a valid HTTP(S) URL.", e);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null)
            throw new IllegalArgumentException(
                    "Viking Memory baseUrl must be a valid HTTP(S) URL.");
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static VikingMemoryConfig fromEnv() {
        return builder().build();
    }

    private String cloudEnvironment(String suffix) {
        return normalize(
                System.getenv(
                        ("byteplus".equalsIgnoreCase(cloudProvider) ? "BYTEPLUS_" : "VOLCENGINE_")
                                + suffix));
    }

    private static String resolve(String explicit, String environmentName, String fallback) {
        String value = normalize(explicit);
        if (value != null) return value;
        value = normalize(System.getenv(environmentName));
        return value == null ? fallback : value;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty()
                        || "none".equalsIgnoreCase(normalized)
                        || "null".equalsIgnoreCase(normalized)
                ? null
                : normalized;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getProject() {
        return project;
    }

    public String getRegion() {
        return region;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getCloudProvider() {
        return cloudProvider;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public List<String> getMemoryTypes() {
        return memoryTypes;
    }

    public boolean hasApiKey() {
        return apiKey != null;
    }

    public boolean hasManagementCredentials() {
        return accessKey != null && secretKey != null;
    }

    public static final class Builder {
        private String apiKey,
                project,
                region,
                memoryTypes,
                baseUrl,
                cloudProvider,
                accessKey,
                secretKey,
                sessionToken;

        public Builder apiKey(String v) {
            apiKey = v;
            return this;
        }

        public Builder project(String v) {
            project = v;
            return this;
        }

        public Builder region(String v) {
            region = v;
            return this;
        }

        public Builder memoryTypes(String v) {
            memoryTypes = v;
            return this;
        }

        public Builder baseUrl(String v) {
            baseUrl = v;
            return this;
        }

        public Builder cloudProvider(String v) {
            cloudProvider = v;
            return this;
        }

        public Builder accessKey(String v) {
            accessKey = v;
            return this;
        }

        public Builder secretKey(String v) {
            secretKey = v;
            return this;
        }

        public Builder sessionToken(String v) {
            sessionToken = v;
            return this;
        }

        public VikingMemoryConfig build() {
            return new VikingMemoryConfig(this);
        }
    }
}
