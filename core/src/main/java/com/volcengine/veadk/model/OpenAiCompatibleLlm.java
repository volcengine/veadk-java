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
import com.google.adk.models.chat.ChatCompletionsClient;
import com.google.adk.models.chat.ChatCompletionsHttpClient;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;
import java.util.Objects;

/** OpenAI-compatible chat-completions adapter backed by ADK Java's HTTP client. */
public final class OpenAiCompatibleLlm extends BaseLlm {

    private final OpenAiCompatibleLlmConfig config;
    private final ChatCompletionsClient client;

    public OpenAiCompatibleLlm(OpenAiCompatibleLlmConfig config) {
        super(Objects.requireNonNull(config, "config must be set.").getModelName());
        this.config = config;
        this.client =
                new ChatCompletionsHttpClient(
                        HttpOptions.builder()
                                .baseUrl(config.getBaseUrl())
                                .headers(Map.of("Authorization", "Bearer " + config.getApiKey()))
                                .build());
    }

    public OpenAiCompatibleLlmConfig config() {
        return config;
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        return client.complete(llmRequest.toBuilder().model(model()).build(), stream);
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException(
                "OpenAI-compatible chat completions does not support live connection.");
    }
}
