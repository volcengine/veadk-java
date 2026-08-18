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
package com.volcengine.veadk.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Loads VeADK settings from {@code config.yaml}, {@code .env}, and process environment. */
public final class VeADKConfig {

    public static final String DEFAULT_MODEL_NAME = "doubao-seed-2-1-pro-260628";
    public static final String DEFAULT_MODEL_PROVIDER = "openai";
    public static final String DEFAULT_MODEL_API_BASE = "https://ark.cn-beijing.volces.com/api/v3/";
    public static final String DEFAULT_EMBEDDING_MODEL = "doubao-embedding-vision-250615";
    public static final int DEFAULT_EMBEDDING_DIMENSION = 2048;

    private static final Set<String> SECRET_MARKERS =
            Set.of("API_KEY", "SECRET", "PASSWORD", "TOKEN", "CREDENTIAL");

    private final Map<String, String> values;

    private VeADKConfig(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static VeADKConfig load() {
        return load(Path.of(""));
    }

    public static VeADKConfig load(Path workingDirectory) {
        return load(workingDirectory, System.getenv());
    }

    /** Loads with an explicit environment map, useful for embedded runtimes and deterministic tests. */
    public static VeADKConfig load(Path workingDirectory, Map<String, String> environment) {
        return new VeADKConfig(ConfigLoader.load(workingDirectory, environment));
    }

    public static VeADKConfig from(Map<String, String> values) {
        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> normalized.put(ConfigLoader.normalize(key), value));
        return new VeADKConfig(normalized);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(ConfigLoader.normalize(key)));
    }

    public String get(String key, String defaultValue) {
        String value = values.get(ConfigLoader.normalize(key));
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public String require(String key) {
        String normalized = ConfigLoader.normalize(key);
        String value = values.get(normalized);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required configuration: "
                            + normalized
                            + ". Configure it in the environment, .env, or config.yaml.");
        }
        return value;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = values.get(ConfigLoader.normalize(key));
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on", "enabled" -> true;
            case "false", "0", "no", "off", "disabled" -> false;
            default ->
                    throw new IllegalArgumentException(
                            "Configuration " + ConfigLoader.normalize(key) + " must be a boolean");
        };
    }

    public int getInt(String key, int defaultValue) {
        String value = values.get(ConfigLoader.normalize(key));
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Configuration " + ConfigLoader.normalize(key) + " must be an integer",
                    exception);
        }
    }

    public ModelConfig model() {
        boolean byteplus = "byteplus".equalsIgnoreCase(get("CLOUD_PROVIDER", ""));
        String defaultName = byteplus ? "seed-2-0-lite-260228" : DEFAULT_MODEL_NAME;
        String defaultBase =
                byteplus
                        ? "https://ark.ap-southeast.bytepluses.com/api/v3"
                        : DEFAULT_MODEL_API_BASE;
        return new ModelConfig(
                get("MODEL_AGENT_NAME", defaultName),
                get("MODEL_AGENT_PROVIDER", DEFAULT_MODEL_PROVIDER),
                get("MODEL_AGENT_API_BASE", defaultBase),
                get("MODEL_AGENT_API_KEY", ""),
                get("MODEL_AGENT_API_KEY_NAME", ""));
    }

    public EmbeddingConfig embedding() {
        boolean byteplus = "byteplus".equalsIgnoreCase(get("CLOUD_PROVIDER", ""));
        return new EmbeddingConfig(
                get(
                        "MODEL_EMBEDDING_NAME",
                        byteplus ? "skylark-embedding-vision-250615" : DEFAULT_EMBEDDING_MODEL),
                getInt("MODEL_EMBEDDING_DIM", DEFAULT_EMBEDDING_DIMENSION),
                get("MODEL_EMBEDDING_API_BASE", model().apiBase()),
                get("MODEL_EMBEDDING_API_KEY", model().apiKey()));
    }

    public OpenTelemetryConfig openTelemetry() {
        return new OpenTelemetryConfig(
                getBoolean("OBSERVABILITY_OPENTELEMETRY_TRACE_CONTENT", true),
                get(
                        "OBSERVABILITY_OPENTELEMETRY_TLS_ENDPOINT",
                        "https://tls-cn-beijing.volces.com:4317"),
                get("OBSERVABILITY_OPENTELEMETRY_TLS_REGION", "cn-beijing"),
                get("OBSERVABILITY_OPENTELEMETRY_TLS_SERVICE_NAME", ""));
    }

    /** Returns all resolved values. Prefer {@link #redactedValues()} for diagnostics. */
    public Map<String, String> values() {
        return values;
    }

    public Map<String, String> redactedValues() {
        Map<String, String> redacted = new LinkedHashMap<>();
        values.forEach(
                (key, value) ->
                        redacted.put(
                                key, isSecret(key) && !value.isBlank() ? "<redacted>" : value));
        return Collections.unmodifiableMap(redacted);
    }

    private static boolean isSecret(String key) {
        String upper = key.toUpperCase(Locale.ROOT);
        return SECRET_MARKERS.stream().anyMatch(upper::contains);
    }

    @Override
    public String toString() {
        return "VeADKConfig" + redactedValues();
    }

    public record ModelConfig(
            String name, String provider, String apiBase, String apiKey, String apiKeyName) {
        @Override
        public String toString() {
            return "ModelConfig[name="
                    + name
                    + ", provider="
                    + provider
                    + ", apiBase="
                    + apiBase
                    + ", apiKey="
                    + (apiKey.isBlank() ? "" : "<redacted>")
                    + ", apiKeyName="
                    + apiKeyName
                    + "]";
        }
    }

    public record EmbeddingConfig(String name, int dimension, String apiBase, String apiKey) {
        @Override
        public String toString() {
            return "EmbeddingConfig[name="
                    + name
                    + ", dimension="
                    + dimension
                    + ", apiBase="
                    + apiBase
                    + ", apiKey="
                    + (apiKey.isBlank() ? "" : "<redacted>")
                    + "]";
        }
    }

    public record OpenTelemetryConfig(
            boolean traceContent, String tlsEndpoint, String tlsRegion, String tlsServiceName) {}
}
