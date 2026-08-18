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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.volcengine.ark.runtime.model.multimodalembeddings.MultimodalEmbedding;
import com.volcengine.ark.runtime.model.multimodalembeddings.MultimodalEmbeddingRequest;
import com.volcengine.ark.runtime.model.multimodalembeddings.MultimodalEmbeddingResult;
import com.volcengine.ark.runtime.model.multimodalembeddings.MultimodalEmbeddingUsage;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.veadk.config.VeADKConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArkEmbeddingTest {

    @Test
    void embedBuildsMultimodalRequestAndReturnsUsage() {
        FakeArkService service = new FakeArkService();
        service.responses.add(response("test-model", List.of(0.1, 0.2), 3, 4));
        ArkEmbedding embedding =
                ArkEmbedding.builder()
                        .modelName("test-model")
                        .dimensions(2)
                        .arkService(service)
                        .build();

        ArkEmbedding.EmbeddingResponse response = embedding.embedWithUsage("hello");

        assertThat(response.embedding()).containsExactly(0.1, 0.2);
        assertThat(response.promptTokens()).isEqualTo(3);
        assertThat(response.totalTokens()).isEqualTo(4);
        MultimodalEmbeddingRequest request = service.requests.get(0);
        assertThat(request.getModel()).isEqualTo("test-model");
        assertThat(request.getDimensions()).isEqualTo(2);
        assertThat(request.getInput()).hasSize(1);
        assertThat(request.getInput().get(0).getType()).isEqualTo("text");
        assertThat(request.getInput().get(0).getText()).isEqualTo("hello");
    }

    @Test
    void batchAndReactiveApisPreserveInputOrder() {
        FakeArkService service = new FakeArkService();
        service.responses.add(response("test-model", List.of(1.0), 0, 0));
        service.responses.add(response("test-model", List.of(2.0), 0, 0));
        service.responses.add(response("test-model", List.of(3.0), 0, 0));
        ArkEmbedding embedding = ArkEmbedding.builder().arkService(service).build();

        assertThat(embedding.getTextEmbeddings(List.of("one", "two")))
                .containsExactly(List.of(1.0), List.of(2.0));
        assertThat(embedding.embedAsync("three").blockingGet()).containsExactly(3.0);
        assertThat(service.requests)
                .extracting(request -> request.getInput().get(0).getText())
                .containsExactly("one", "two", "three");
    }

    @Test
    void malformedResponseFailsWithActionableError() {
        FakeArkService service = new FakeArkService();
        service.responses.add(new MultimodalEmbeddingResult());
        ArkEmbedding embedding = ArkEmbedding.builder().arkService(service).build();

        assertThatThrownBy(() -> embedding.embed("text"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not contain an embedding");
    }

    @Test
    void configuredBuilderUsesEmbeddingSettings() {
        VeADKConfig config =
                VeADKConfig.from(
                        Map.of(
                                "MODEL_EMBEDDING_NAME", "configured-embedding",
                                "MODEL_EMBEDDING_DIM", "512",
                                "MODEL_EMBEDDING_API_KEY", "test-key",
                                "MODEL_EMBEDDING_API_BASE", "https://ark.example/api/v3"));

        ArkEmbedding embedding = ArkEmbedding.builder(config).build();

        assertThat(embedding.modelName()).isEqualTo("configured-embedding");
        assertThat(embedding.dimensions()).isEqualTo(512);
    }

    @Test
    void configuredBuilderFallsBackToAgentApiKey() {
        VeADKConfig config =
                VeADKConfig.from(
                        Map.of(
                                "MODEL_EMBEDDING_NAME", "configured-embedding",
                                "MODEL_AGENT_API_KEY", "agent-key"));

        ArkEmbedding embedding = ArkEmbedding.builder(config).build();

        assertThat(embedding.modelName()).isEqualTo("configured-embedding");
    }

    private static MultimodalEmbeddingResult response(
            String model, List<Double> vector, long promptTokens, long totalTokens) {
        MultimodalEmbedding data = new MultimodalEmbedding();
        data.setEmbedding(vector);
        MultimodalEmbeddingUsage usage = new MultimodalEmbeddingUsage();
        usage.setPromptTokens(promptTokens);
        usage.setTotalTokens(totalTokens);
        MultimodalEmbeddingResult result = new MultimodalEmbeddingResult();
        result.setModel(model);
        result.setData(data);
        result.setUsage(usage);
        return result;
    }

    private static final class FakeArkService extends ArkService {
        private final List<MultimodalEmbeddingRequest> requests = new ArrayList<>();
        private final Deque<MultimodalEmbeddingResult> responses = new ArrayDeque<>();

        private FakeArkService() {
            super("test-key");
        }

        @Override
        public MultimodalEmbeddingResult createMultiModalEmbeddings(
                MultimodalEmbeddingRequest request) {
            requests.add(request);
            return responses.removeFirst();
        }
    }
}
