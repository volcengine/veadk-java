/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Initial session metadata for an evaluation case. */
public record SessionInput(
        @JsonProperty("app_name") String appName,
        @JsonProperty("user_id") String userId,
        Map<String, Object> state) {
    public SessionInput {
        state = state == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(state));
    }
}
