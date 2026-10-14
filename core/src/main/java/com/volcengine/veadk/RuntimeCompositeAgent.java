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
package com.volcengine.veadk;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Objects;

/** Transfer target that delegates execution to a dynamically built ADK workflow root. */
public final class RuntimeCompositeAgent extends BaseAgent {

    private final BaseAgent delegate;

    public RuntimeCompositeAgent(String name, String description, BaseAgent delegate) {
        super(
                name,
                Objects.requireNonNullElse(description, ""),
                List.of(Objects.requireNonNull(delegate, "delegate must be set.")),
                List.of(),
                List.of());
        this.delegate = delegate;
    }

    void attachTo(BaseAgent parent) {
        parentAgent(parent);
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
        return delegate.runAsync(invocationContext);
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
        return delegate.runLive(invocationContext);
    }
}
