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

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.AgentMetadata;
import com.volcengine.veadk.AgentMetadataExtractor;
import com.volcengine.veadk.utils.JSONUtil;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;

public class AgentFacadeExample {

    public static void main(String[] args) {
        Agent agent = buildWithCustomModel();
        AgentMetadata metadata = AgentMetadataExtractor.extract(agent);
        System.out.println(JSONUtil.toJson(metadata));
    }

    static Agent buildWithModelName() {
        return Agent.builder()
                .name("facade-model-name-agent")
                .description("Minimal Agent facade example using a model name.")
                .instruction("You are a helpful assistant.")
                .modelName("doubao-seed-2-1-pro-260628")
                .tools(new EchoTool())
                .build();
    }

    static Agent buildWithCustomModel() {
        return Agent.builder()
                .name("facade-custom-model-agent")
                .description("Minimal Agent facade example using a BaseLlm instance.")
                .instruction("You are a helpful assistant.")
                .model(new NoopLlm("example-noop-model"))
                .tools(new EchoTool())
                .build();
    }

    private static final class NoopLlm extends BaseLlm {
        private NoopLlm(String model) {
            super(model);
        }

        @Override
        public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
            return Flowable.empty();
        }

        @Override
        public BaseLlmConnection connect(LlmRequest llmRequest) {
            throw new UnsupportedOperationException(
                    "This example does not open a live LLM connection.");
        }
    }

    private static final class EchoTool extends BaseTool {
        private EchoTool() {
            super("echo", "Return the provided text.");
        }

        @Override
        public Single<Map<String, Object>> runAsync(
                Map<String, Object> args, ToolContext toolContext) {
            return Single.just(Map.of("text", String.valueOf(args.getOrDefault("text", ""))));
        }
    }
}
