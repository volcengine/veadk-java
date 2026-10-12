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

import com.google.adk.agents.Callbacks;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.Maybe;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Session-scoped Responses continuation, compatible with ADK Java 1.9. */
public final class ArkResponsesSession {
    static final String CONTEXT = "veadk_ark_response_context";
    static final String RESPONSE_ID = "veadk_ark_response_id";

    /** Attach to beforeModelCallback; reads only this session and agent's state. */
    public Callbacks.BeforeModelCallback beforeModel() {
        return (context, builder) -> {
            Object previous = context.state().get(CONTEXT + ":" + context.agentName());
            if (previous instanceof String token) attach(builder, token);
            return Maybe.empty();
        };
    }

    /** Attach to afterModelCallback; saves continuation only after a complete response. */
    public Callbacks.AfterModelCallback afterModel() {
        return (context, response) -> {
            if (!response.partial().orElse(false)) {
                String key = CONTEXT + ":" + context.agentName();
                Optional<String> token = metadata(response, CONTEXT);
                if (token.isPresent()) context.state().put(key, token.get());
                else context.state().remove(key);
            }
            return Maybe.empty();
        };
    }

    /** For direct model calls: retain full history, then attach the preceding response. */
    public static LlmRequest continueFrom(LlmRequest request, LlmResponse previous) {
        var builder = request.toBuilder();
        metadata(previous, CONTEXT).ifPresent(token -> attach(builder, token));
        return builder.build();
    }

    public static Optional<String> responseId(LlmResponse response) {
        return metadata(response, RESPONSE_ID);
    }

    static Optional<String> metadata(LlmResponse response, String key) {
        return response.customMetadata().stream()
                .flatMap(java.util.Collection::stream)
                .filter(value -> value.key().filter(key::equals).isPresent())
                .flatMap(value -> value.stringValue().stream())
                .findFirst();
    }

    private static void attach(LlmRequest.Builder builder, String token) {
        GenerateContentConfig config =
                builder.build().config().orElseGet(() -> GenerateContentConfig.builder().build());
        HttpOptions http = config.httpOptions().orElseGet(() -> HttpOptions.builder().build());
        Map<String, Object> extra = new LinkedHashMap<>(http.extraBody().orElse(Map.of()));
        extra.put(CONTEXT, token);
        builder.config(
                config.toBuilder().httpOptions(http.toBuilder().extraBody(extra).build()).build());
    }
}
