/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** A multi-turn evaluation case. */
public record EvalCase(
        @JsonProperty("eval_id") String evalId,
        List<EvalInvocation> conversation,
        @JsonProperty("session_input") SessionInput sessionInput) {
    public EvalCase {
        conversation = conversation == null ? List.of() : List.copyOf(conversation);
    }
}
