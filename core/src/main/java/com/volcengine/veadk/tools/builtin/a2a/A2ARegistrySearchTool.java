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
package com.volcengine.veadk.tools.builtin.a2a;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.volcengine.veadk.a2a.registry.AgentKitA2ARegistryClient;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Built-in tool for semantic search over AgentKit's remote A2A registry. */
public final class A2ARegistrySearchTool extends BaseTool implements AutoCloseable {

    private final AgentKitA2ARegistryClient client;

    public A2ARegistrySearchTool() {
        this(new AgentKitA2ARegistryClient());
    }

    public A2ARegistrySearchTool(AgentKitA2ARegistryClient client) {
        super(
                "a2a_registry_search_agent_cards",
                "Search the AgentKit A2A registry for remote agents that can handle a task. Pass a"
                    + " concise search prompt, inspect each returned name, description, and skills,"
                    + " and do not pass the complete request when a short summary is sufficient.",
                false);
        this.client = Objects.requireNonNull(client, "client must be set.");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(
                FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(
                                Schema.builder()
                                        .type("OBJECT")
                                        .properties(
                                                ImmutableMap.of(
                                                        "prompt",
                                                        Schema.builder()
                                                                .type("STRING")
                                                                .description(
                                                                        "Concise task keywords or"
                                                                                + " description, at"
                                                                                + " most 2048 UTF-8"
                                                                                + " bytes.")
                                                                .build()))
                                        .required(ImmutableList.of("prompt"))
                                        .build())
                        .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
        return Single.fromCallable(
                () -> {
                    try {
                        return client.searchAgentCards(
                                String.valueOf(args.getOrDefault("prompt", "")));
                    } catch (IllegalArgumentException e) {
                        return failure("INVALID_ARGUMENT", e.getMessage());
                    } catch (Exception e) {
                        return failure("AGENTKIT_OPENAPI_FAILED", safeMessage(e));
                    }
                });
    }

    @Override
    public void close() {
        client.close();
    }

    private static Map<String, Object> failure(String code, String message) {
        return Map.of(
                "outcome",
                "failure",
                "error_code",
                code,
                "error_message",
                Objects.requireNonNullElse(message, "request failed"),
                "diagnostics",
                Map.of());
    }

    private static String safeMessage(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
