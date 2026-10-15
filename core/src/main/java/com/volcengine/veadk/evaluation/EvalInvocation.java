/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One user turn and its expected agent behavior. */
public record EvalInvocation(
        @JsonProperty("invocation_id") String invocationId,
        @JsonProperty("user_content") EvalContent userContent,
        @JsonProperty("final_response") EvalContent finalResponse,
        @JsonProperty("intermediate_data") IntermediateData intermediateData) {}
