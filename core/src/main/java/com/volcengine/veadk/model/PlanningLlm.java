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
package com.volcengine.veadk.model;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.volcengine.veadk.planners.Planner;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Objects;

/** Applies a planner around any ADK model while retaining the normal Agent/Runner tool loop. */
public final class PlanningLlm extends BaseLlm implements AutoCloseable {
    private final BaseLlm delegate;
    private final Planner planner;

    public PlanningLlm(BaseLlm delegate, Planner planner) {
        super(Objects.requireNonNull(delegate, "delegate must be set").model());
        this.delegate = delegate;
        this.planner = Objects.requireNonNull(planner, "planner must be set");
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        return Flowable.defer(
                () -> {
                    String instruction = planner.buildPlanningInstruction(request);
                    LlmRequest planned =
                            instruction == null || instruction.isBlank()
                                    ? request
                                    : request.toBuilder()
                                            .appendInstructions(List.of(instruction))
                                            .build();
                    return delegate.generateContent(planned, stream)
                            .map(planner::processPlanningResponse);
                });
    }

    @Override
    public BaseLlmConnection connect(LlmRequest request) {
        throw new UnsupportedOperationException("Planning supports generateContent only");
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
