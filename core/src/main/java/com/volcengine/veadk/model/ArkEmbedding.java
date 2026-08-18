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

import com.volcengine.ark.runtime.model.multimodalembeddings.MultimodalEmbeddingInput;
import com.volcengine.ark.runtime.model.multimodalembeddings.MultimodalEmbeddingRequest;
import com.volcengine.ark.runtime.model.multimodalembeddings.MultimodalEmbeddingResult;
import com.volcengine.ark.runtime.model.multimodalembeddings.MultimodalEmbeddingUsage;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.veadk.config.VeADKConfig;
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Ark multimodal embedding adapter with synchronous, reactive, and batched text APIs. */
public final class ArkEmbedding {

    public enum Model {
        DOUBAO_EMBEDDING_VISION_251215("doubao-embedding-vision-251215"),
        DOUBAO_EMBEDDING_VISION_250615("doubao-embedding-vision-250615");

        private final String id;

        Model(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    private final String modelName;
    private final Integer dimensions;
    private final ArkService arkService;

    public ArkEmbedding(String modelName, String apiKey) {
        this(builder().modelName(modelName).apiKey(apiKey));
    }

    public ArkEmbedding(String modelName, String apiKey, String apiBase, Integer dimensions) {
        this(builder().modelName(modelName).apiKey(apiKey).apiBase(apiBase).dimensions(dimensions));
    }

    private ArkEmbedding(Builder builder) {
        modelName = requireText(builder.modelName, "modelName");
        dimensions = builder.dimensions;
        if (dimensions != null && dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        arkService = builder.arkService != null ? builder.arkService : createService(builder);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(VeADKConfig config) {
        Objects.requireNonNull(config, "config");
        VeADKConfig.EmbeddingConfig embedding = config.embedding();
        return new Builder()
                .modelName(embedding.name())
                .apiKey(embedding.apiKey())
                .apiBase(embedding.apiBase())
                .dimensions(embedding.dimension());
    }

    public String modelName() {
        return modelName;
    }

    public Integer dimensions() {
        return dimensions;
    }

    public List<Double> embed(String text) {
        return embedWithUsage(text).embedding();
    }

    public EmbeddingResponse embedWithUsage(String text) {
        Objects.requireNonNull(text, "text");
        MultimodalEmbeddingInput input =
                MultimodalEmbeddingInput.builder().type("text").text(text).build();
        MultimodalEmbeddingRequest.Builder request =
                MultimodalEmbeddingRequest.builder().model(modelName).input(List.of(input));
        if (dimensions != null) {
            request.dimensions(dimensions);
        }
        MultimodalEmbeddingResult result = arkService.createMultiModalEmbeddings(request.build());
        if (result == null || result.getData() == null || result.getData().getEmbedding() == null) {
            throw new IllegalStateException("Ark embedding response did not contain an embedding");
        }
        MultimodalEmbeddingUsage usage = result.getUsage();
        return new EmbeddingResponse(
                result.getModel() == null ? modelName : result.getModel(),
                result.getData().getEmbedding(),
                usage == null ? 0 : usage.getPromptTokens(),
                usage == null ? 0 : usage.getTotalTokens());
    }

    public List<List<Double>> embedAll(List<String> texts) {
        Objects.requireNonNull(texts, "texts");
        List<List<Double>> embeddings = new ArrayList<>(texts.size());
        for (String text : texts) {
            embeddings.add(embed(text));
        }
        return List.copyOf(embeddings);
    }

    public Single<List<Double>> embedAsync(String text) {
        return Single.fromCallable(() -> embed(text));
    }

    public Single<List<List<Double>>> embedAllAsync(List<String> texts) {
        return Single.fromCallable(() -> embedAll(texts));
    }

    public List<Double> getTextEmbedding(String text) {
        return embed(text);
    }

    public List<List<Double>> getTextEmbeddings(List<String> texts) {
        return embedAll(texts);
    }

    public List<Double> getQueryEmbedding(String query) {
        return embed(query);
    }

    private static ArkService createService(Builder builder) {
        ArkService.Builder service =
                ArkService.builder()
                        .apiKey(requireText(builder.apiKey, "apiKey"))
                        .timeout(builder.timeout)
                        .retryTimes(builder.maxRetries);
        if (builder.apiBase != null && !builder.apiBase.isBlank()) {
            service.baseUrl(builder.apiBase);
        }
        return service.build();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public record EmbeddingResponse(
            String model, List<Double> embedding, long promptTokens, long totalTokens) {
        public EmbeddingResponse {
            model = Objects.requireNonNullElse(model, "");
            embedding = List.copyOf(embedding);
        }
    }

    public static final class Builder {
        private String modelName = VeADKConfig.DEFAULT_EMBEDDING_MODEL;
        private String apiKey;
        private String apiBase;
        private Integer dimensions;
        private int maxRetries = 10;
        private Duration timeout = Duration.ofSeconds(60);
        private ArkService arkService;

        private Builder() {}

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder model(Model model) {
            return modelName(Objects.requireNonNull(model, "model").id());
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder apiBase(String apiBase) {
            this.apiBase = apiBase;
            return this;
        }

        public Builder dimensions(Integer dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must not be negative");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** Supplies a preconfigured client, primarily for custom transports and deterministic tests. */
        public Builder arkService(ArkService arkService) {
            this.arkService = Objects.requireNonNull(arkService, "arkService");
            return this;
        }

        public ArkEmbedding build() {
            return new ArkEmbedding(this);
        }
    }
}
