/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. Licensed under Apache 2.0. */
package com.volcengine.veadk.tools.builtin.media;

import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.veadk.model.ArkLlmConfig;
import java.time.Duration;

final class ArkMediaServiceFactory {
    private ArkMediaServiceFactory() {}

    static ArkService create(String keyVariable, String baseVariable) {
        String key = first(keyVariable, "MODEL_AGENT_API_KEY");
        if (key == null)
            throw new IllegalStateException(
                    "Missing required configuration: " + keyVariable + " or MODEL_AGENT_API_KEY");
        String base = first(baseVariable, ArkLlmConfig.MODEL_AGENT_API_BASE);
        if (base == null) base = ArkLlmConfig.DEFAULT_API_BASE;
        return ArkService.builder()
                .apiKey(key)
                .baseUrl(base)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    static String model(String variable, String fallback) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String first(String... variables) {
        for (String variable : variables) {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
