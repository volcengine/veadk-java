/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Intermediate agent trajectory captured for evaluation. */
public record IntermediateData(@JsonProperty("tool_uses") List<ToolInvocation> toolUses) {
    public IntermediateData {
        toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
    }
}
