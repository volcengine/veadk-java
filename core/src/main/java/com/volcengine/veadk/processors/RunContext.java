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
package com.volcengine.veadk.processors;

import com.google.adk.agents.RunConfig;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.volcengine.veadk.runner.Runner;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable inputs made available to a {@link BaseRunProcessor}. */
public record RunContext(
        Runner runner,
        Session session,
        Content message,
        RunConfig runConfig,
        Map<String, Object> invocationState) {

    public RunContext {
        Objects.requireNonNull(runner, "runner");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(runConfig, "runConfig");
        invocationState =
                invocationState == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(invocationState));
    }

    public String appName() {
        return session.appName();
    }

    public String userId() {
        return session.userId();
    }

    public String sessionId() {
        return session.id();
    }
}
