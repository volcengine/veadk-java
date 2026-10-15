/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Google ADK-compatible evaluation set. */
public record EvalSet(
        @JsonProperty("eval_set_id") String evalSetId,
        String name,
        String description,
        @JsonProperty("eval_cases") List<EvalCase> evalCases) {
    public EvalSet {
        evalCases = evalCases == null ? List.of() : List.copyOf(evalCases);
    }
}
