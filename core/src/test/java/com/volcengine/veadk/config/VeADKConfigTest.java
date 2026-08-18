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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VeADKConfigTest {

    @TempDir Path directory;

    @Test
    void load_resolvesYamlDotenvAndEnvironmentWithExpectedPrecedence() throws Exception {
        Files.writeString(
                directory.resolve("config.yaml"),
                """
                model:
                  agent:
                    name: yaml-model
                    api_base: https://yaml.example/v3
                    api_key: yaml-secret
                  embedding:
                    dim: 1024
                observability:
                  opentelemetry:
                    trace_content: false
                """);
        Files.writeString(
                directory.resolve(".env"),
                "MODEL_AGENT_NAME=dotenv-model\nMODEL_AGENT_API_KEY=dotenv-secret\n");

        VeADKConfig config =
                VeADKConfig.load(
                        directory,
                        Map.of(
                                "MODEL_AGENT_NAME", "environment-model",
                                "MODEL_AGENT_API_KEY", "environment-secret"));

        assertThat(config.model().name()).isEqualTo("environment-model");
        assertThat(config.model().apiBase()).isEqualTo("https://yaml.example/v3");
        assertThat(config.model().apiKey()).isEqualTo("environment-secret");
        assertThat(config.embedding().dimension()).isEqualTo(1024);
        assertThat(config.openTelemetry().traceContent()).isFalse();
    }

    @Test
    void load_findsParentYamlAndSupportsByteplusCredentialAliases() throws Exception {
        Files.writeString(
                directory.resolve("config.yaml"),
                "cloud_provider: byteplus\n"
                        + "byteplus_access_key: bp-ak\n"
                        + "byteplus_secret_key: bp-sk\n");
        Path child = Files.createDirectories(directory.resolve("nested/agent"));

        VeADKConfig config = VeADKConfig.load(child, Map.of());

        assertThat(config.require("VOLCENGINE_ACCESS_KEY")).isEqualTo("bp-ak");
        assertThat(config.require("VOLCENGINE_SECRET_KEY")).isEqualTo("bp-sk");
        assertThat(config.model().name()).isEqualTo("seed-2-0-lite-260228");
        assertThat(config.embedding().name()).isEqualTo("skylark-embedding-vision-250615");
    }

    @Test
    void diagnosticsRedactSecretsWithoutHidingNormalConfiguration() {
        VeADKConfig config =
                VeADKConfig.from(
                        Map.of(
                                "MODEL_AGENT_NAME", "test-model",
                                "MODEL_AGENT_API_KEY", "super-secret",
                                "DATABASE_REDIS_PASSWORD", "redis-secret"));

        assertThat(config.toString()).contains("test-model", "<redacted>");
        assertThat(config.toString()).doesNotContain("super-secret", "redis-secret");
        assertThat(config.model().toString()).doesNotContain("super-secret");
    }

    @Test
    void typedAccessorsRejectInvalidValuesAndMissingRequiredValues() {
        VeADKConfig config =
                VeADKConfig.from(
                        Map.of(
                                "FEATURE_ENABLED", "sometimes",
                                "RETRY_COUNT", "many"));

        assertThatThrownBy(() -> config.getBoolean("FEATURE_ENABLED", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.getInt("RETRY_COUNT", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.require("MISSING_VALUE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MISSING_VALUE");
    }
}
