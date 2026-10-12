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
package com.volcengine.veadk.planners;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;

/**
 * Model planning extension for ADK Java 1.9, which does not expose Python's BasePlanner API.
 * Implementations must be safe to share across concurrent invocations.
 */
public interface Planner {
    /** Returns instructions to append to the existing system instructions. */
    String buildPlanningInstruction(LlmRequest request);

    /** Processes a response without discarding tool calls, usage, or provider metadata. */
    LlmResponse processPlanningResponse(LlmResponse response);
}
