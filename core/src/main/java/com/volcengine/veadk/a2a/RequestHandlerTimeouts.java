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
package com.volcengine.veadk.a2a;

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.events.QueueManager;
import io.a2a.server.requesthandlers.DefaultRequestHandler;
import io.a2a.server.tasks.TaskStore;
import java.lang.reflect.Field;
import java.util.concurrent.Executor;

/** Bridges A2A SDK 0.3.2 timeout fields that do not yet have a public constructor option. */
final class RequestHandlerTimeouts {

    private RequestHandlerTimeouts() {}

    static DefaultRequestHandler create(
            AgentExecutor agentExecutor,
            TaskStore taskStore,
            QueueManager queueManager,
            Executor executor,
            int agentTimeoutSeconds) {
        DefaultRequestHandler handler =
                new DefaultRequestHandler(
                        agentExecutor, taskStore, queueManager, null, null, executor);
        set(handler, "agentCompletionTimeoutSeconds", agentTimeoutSeconds);
        set(handler, "consumptionCompletionTimeoutSeconds", Math.min(agentTimeoutSeconds, 30));
        return handler;
    }

    private static void set(DefaultRequestHandler handler, String fieldName, int value) {
        try {
            Field field = DefaultRequestHandler.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setInt(handler, value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException(
                    "A2A SDK no longer exposes the expected request timeout field " + fieldName, e);
        }
    }
}
