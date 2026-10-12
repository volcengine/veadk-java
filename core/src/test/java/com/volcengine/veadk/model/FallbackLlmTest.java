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

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.PublishProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class FallbackLlmTest {
    private final LlmRequest request =
            LlmRequest.builder()
                    .model("primary")
                    .contents(List.of(Content.fromParts(Part.fromText("hello"))))
                    .config(GenerateContentConfig.builder().temperature(0.2f).build())
                    .build();
    private final LlmResponse response =
            LlmResponse.builder().content(Content.fromParts(Part.fromText("ok"))).build();

    @Test
    void selectsFallbackInOrderAndPreservesRequest() {
        Stub primary =
                new Stub("primary", r -> Flowable.error(new IllegalStateException("offline")));
        Stub second = new Stub("second", r -> Flowable.error(new IllegalStateException("offline")));
        Stub third = new Stub("third", r -> Flowable.just(response));
        new FallbackLlm(primary, List.of(second, third))
                .generateContent(request, true)
                .test()
                .assertResult(response);
        assertThat(third.requests.get(0).model()).contains("third");
        assertThat(third.requests.get(0).contents()).isEqualTo(request.contents());
        assertThat(third.requests.get(0).config()).isEqualTo(request.config());
        assertThat(third.stream).isTrue();
        assertThat(request.model()).contains("primary");
    }

    @Test
    void handlesSynchronousGenerationFailure() {
        Stub primary =
                new Stub(
                        "primary",
                        r -> {
                            throw new IllegalStateException("offline");
                        });
        Stub backup = new Stub("backup", r -> Flowable.just(response));
        new FallbackLlm(primary, List.of(backup))
                .generateContent(request, false)
                .test()
                .assertResult(response);
    }

    @Test
    void doesNotFallbackAfterAnyResponseIncludingEmptyPartial() {
        RuntimeException error = new IllegalStateException("interrupted");
        LlmResponse partial = LlmResponse.builder().partial(true).build();
        Stub primary =
                new Stub("primary", r -> Flowable.just(partial).concatWith(Flowable.error(error)));
        Stub backup = new Stub("backup", r -> Flowable.just(response));
        new FallbackLlm(primary, List.of(backup))
                .generateContent(request, true)
                .test()
                .assertValue(partial)
                .assertError(error)
                .assertNotComplete();
        assertThat(backup.requests).isEmpty();
    }

    @Test
    void returnsLastFailureWhenAllModelsFail() {
        RuntimeException error = new IllegalStateException("last");
        Stub primary = new Stub("primary", r -> Flowable.error(new IllegalStateException("first")));
        Stub backup = new Stub("backup", r -> Flowable.error(error));
        new FallbackLlm(primary, List.of(backup))
                .generateContent(request, false)
                .test()
                .assertNoValues()
                .assertError(error);
    }

    @Test
    void successfulPrimaryDoesNotCallBackup() {
        Stub primary = new Stub("primary", r -> Flowable.just(response));
        Stub backup = new Stub("backup", r -> Flowable.just(response));
        new FallbackLlm(primary, List.of(backup))
                .generateContent(request, false)
                .test()
                .assertResult(response);
        assertThat(backup.requests).isEmpty();
    }

    @Test
    void eachSubscriptionStartsWithPrimaryAndHasIndependentEmissionState() {
        Stub primary = new Stub("primary", r -> Flowable.error(new IllegalStateException("first")));
        Stub backup = new Stub("backup", r -> Flowable.just(response));
        Flowable<LlmResponse> stream =
                new FallbackLlm(primary, List.of(backup)).generateContent(request, true);
        stream.test().assertResult(response);
        stream.test().assertResult(response);
        assertThat(primary.requests).hasSize(2);
        assertThat(backup.requests).hasSize(2);
    }

    @Test
    void cancellationDoesNotStartFallback() {
        PublishProcessor<LlmResponse> processor = PublishProcessor.create();
        Stub primary = new Stub("primary", r -> processor);
        Stub backup = new Stub("backup", r -> Flowable.just(response));
        var subscriber =
                new FallbackLlm(primary, List.of(backup)).generateContent(request, true).test();
        assertThat(processor.hasSubscribers()).isTrue();
        subscriber.cancel();
        assertThat(processor.hasSubscribers()).isFalse();
        assertThat(backup.requests).isEmpty();
    }

    @Test
    void closesUniqueModelsAndContinuesAfterCloseFailure() {
        Stub primary = new Stub("primary", r -> Flowable.just(response));
        Stub backup = new Stub("backup", r -> Flowable.just(response));
        primary.closeError = new IllegalStateException("close primary");
        backup.closeError = new IllegalStateException("close backup");
        FallbackLlm llm = new FallbackLlm(primary, List.of(primary, backup));
        assertThatThrownBy(llm::close)
                .isSameAs(primary.closeError)
                .satisfies(
                        error ->
                                assertThat(error.getSuppressed())
                                        .containsExactly(backup.closeError));
        assertThat(primary.closes).isEqualTo(1);
        assertThat(backup.closes).isEqualTo(1);
    }

    static final class Stub extends BaseLlm implements AutoCloseable {
        final List<LlmRequest> requests = new ArrayList<>();
        final Function<LlmRequest, Flowable<LlmResponse>> generate;
        boolean stream;
        int closes;
        RuntimeException closeError;

        Stub(String name, Function<LlmRequest, Flowable<LlmResponse>> generate) {
            super(name);
            this.generate = generate;
        }

        @Override
        public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
            requests.add(request);
            this.stream = stream;
            return generate.apply(request);
        }

        @Override
        public BaseLlmConnection connect(LlmRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            closes++;
            if (closeError != null) throw closeError;
        }
    }
}
