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
package com.volcengine.veadk.example;

import com.volcengine.veadk.Agent;

/** Minimal Agent configuration for OpenAI-compatible chat-completions endpoints. */
public class OpenAiCompatibleAgent {

    public static Agent buildOpenAiAgent() {
        return Agent.builder()
                .name("openai_agent")
                .instruction("You are a helpful assistant.")
                .model("openai/gpt-4o")
                .modelApiKey(System.getenv("OPENAI_API_KEY"))
                .modelBaseUrl("https://api.openai.com/v1")
                .build();
    }

    public static Agent buildLiteLlmProxyAgent() {
        return Agent.builder()
                .name("litellm_proxy_agent")
                .instruction("You are a helpful assistant.")
                .modelProvider("openai")
                .model("anthropic/claude-sonnet-4")
                .modelApiKey(System.getenv("LITELLM_API_KEY"))
                .modelBaseUrl("http://localhost:4000/v1")
                .build();
    }
}
