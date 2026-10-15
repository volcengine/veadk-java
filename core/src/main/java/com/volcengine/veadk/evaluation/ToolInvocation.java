/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.evaluation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Expected or observed tool invocation. */
public record ToolInvocation(String id, Map<String, Object> args, String name) {
    public ToolInvocation {
        args = args == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }
}
