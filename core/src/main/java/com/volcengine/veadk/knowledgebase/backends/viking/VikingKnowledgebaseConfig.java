/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.volcengine.veadk.knowledgebase.backends.viking;

import java.net.URI;

public final class VikingKnowledgebaseConfig {

    public static final boolean DEFAULT_RERANK = true;
    public static final int DEFAULT_CHUNK_DIFFUSION_COUNT = 3;

    private final String apiKey;
    private final String project;
    private final String region;
    private final String resourceId;
    private final String version;
    private final String baseUrl;
    private final String cloudProvider;
    private final String accessKey;
    private final String secretKey;
    private final String sessionToken;
    private final boolean rerank;
    private final int chunkDiffusionCount;

    public VikingKnowledgebaseConfig(
            String accessKey, String secretKey, boolean rerank, int chunkDiffusionCount) {
        this(
                builder()
                        .accessKey(accessKey)
                        .secretKey(secretKey)
                        .rerank(rerank)
                        .chunkDiffusionCount(chunkDiffusionCount));
    }

    private VikingKnowledgebaseConfig(Builder builder) {
        cloudProvider =
                resolve(
                        builder.cloudProvider,
                        "AGENTKIT_CLOUD_PROVIDER",
                        resolve(null, "CLOUD_PROVIDER", "volcengine"));
        String configuredRegion =
                resolve(
                        builder.region,
                        "DATABASE_VIKING_REGION",
                        resolve(null, "REGION", "cn-beijing"));
        region =
                "byteplus".equalsIgnoreCase(cloudProvider)
                                && (configuredRegion == null
                                        || configuredRegion.startsWith("cn-")
                                                && !"cn-hongkong".equals(configuredRegion))
                        ? "cn-hongkong"
                        : configuredRegion;
        apiKey = resolve(builder.apiKey, "DATABASE_VIKING_API_KEY", null);
        project = resolve(builder.project, "DATABASE_VIKING_PROJECT", "default");
        resourceId = resolve(builder.resourceId, "DATABASE_VIKING_RESOURCE_ID", null);
        version = resolve(builder.version, "DATABASE_VIKING_VERSION", "2");
        accessKey =
                normalize(builder.accessKey) == null
                        ? cloudEnvironment("ACCESS_KEY")
                        : normalize(builder.accessKey);
        secretKey =
                normalize(builder.secretKey) == null
                        ? cloudEnvironment("SECRET_KEY")
                        : normalize(builder.secretKey);
        sessionToken =
                normalize(builder.sessionToken) == null
                        ? cloudEnvironment("SESSION_TOKEN")
                        : normalize(builder.sessionToken);
        String endpoint =
                "https://api-knowledgebase.mlp."
                        + region
                        + ("byteplus".equalsIgnoreCase(cloudProvider)
                                ? ".bytepluses.com"
                                : ".volces.com");
        baseUrl = validateBaseUrl(resolve(builder.baseUrl, "DATABASE_VIKING_BASE_URL", endpoint));
        rerank = builder.rerank;
        chunkDiffusionCount = builder.chunkDiffusionCount;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static VikingKnowledgebaseConfig fromEnv() {
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

    private static String validateBaseUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Viking baseUrl must be a valid HTTP(S) URL.", e);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("Viking baseUrl must be a valid HTTP(S) URL.");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
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

    public String getResourceId() {
        return resourceId;
    }

    public String getVersion() {
        return version;
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

    public boolean isRerank() {
        return rerank;
    }

    public int getChunkDiffusionCount() {
        return chunkDiffusionCount;
    }

    public boolean hasApiKey() {
        return apiKey != null;
    }

    public boolean hasManagementCredentials() {
        return accessKey != null && secretKey != null;
    }

    public static final class Builder {
        private String apiKey;
        private String project;
        private String region;
        private String resourceId;
        private String version;
        private String baseUrl;
        private String cloudProvider;
        private String accessKey;
        private String secretKey;
        private String sessionToken;
        private boolean rerank = DEFAULT_RERANK;
        private int chunkDiffusionCount = DEFAULT_CHUNK_DIFFUSION_COUNT;

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

        public Builder resourceId(String v) {
            resourceId = v;
            return this;
        }

        public Builder version(String v) {
            version = v;
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

        public Builder rerank(boolean v) {
            rerank = v;
            return this;
        }

        public Builder chunkDiffusionCount(int v) {
            chunkDiffusionCount = v;
            return this;
        }

        public VikingKnowledgebaseConfig build() {
            return new VikingKnowledgebaseConfig(this);
        }
    }
}
