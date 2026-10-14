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
package com.volcengine.veadk.a2a.registry;

import com.volcengine.veadk.utils.EnvUtil;

/** Configuration for AgentKit A2A semantic discovery. */
public record AgentKitA2ARegistryConfig(String spaceId, String endpoint, String region, int topK) {

    public AgentKitA2ARegistryConfig {
        spaceId = spaceId == null ? "" : spaceId.strip();
        endpoint =
                endpoint == null || endpoint.isBlank()
                        ? EnvUtil.getAgentKitManagementHost()
                        : endpoint.strip();
        region = region == null || region.isBlank() ? "cn-beijing" : region.strip();
        topK = Math.max(1, Math.min(topK, 20));
    }

    public static AgentKitA2ARegistryConfig fromEnv() {
        return new AgentKitA2ARegistryConfig(
                first("REGISTRY_SPACE_ID", "AGENTKIT_A2A_SPACE_ID", "A2A_REGISTRY_SPACE_ID"),
                firstOrDefault(
                        EnvUtil.getAgentKitManagementHost(),
                        "REGISTRY_ENDPOINT",
                        "AGENTKIT_OPENAPI_ENDPOINT"),
                firstOrDefault("cn-beijing", "REGISTRY_REGION", "AGENTKIT_REGION"),
                integer("REGISTRY_TOP_K", 3));
    }

    private static String first(String... names) {
        return firstOrDefault("", names);
    }

    private static String firstOrDefault(String fallback, String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    private static int integer(String name, int fallback) {
        try {
            return Integer.parseInt(System.getenv().getOrDefault(name, String.valueOf(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
