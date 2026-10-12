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
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Ordered model failover before the first response, with independent endpoint configuration. */
public final class FallbackLlm extends BaseLlm implements AutoCloseable {
    private final List<BaseLlm> models;

    public FallbackLlm(BaseLlm primary, List<? extends BaseLlm> fallbacks) {
        super(Objects.requireNonNull(primary, "primary must be set").model());
        List<BaseLlm> configured = new ArrayList<>();
        configured.add(primary);
        configured.addAll(List.copyOf(fallbacks));
        this.models = List.copyOf(configured);
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        Objects.requireNonNull(request, "request must be set");
        return attempt(request, stream, 0);
    }

    private Flowable<LlmResponse> attempt(LlmRequest request, boolean stream, int index) {
        return Flowable.defer(
                () -> {
                    boolean[] emitted = {false};
                    BaseLlm current = models.get(index);
                    return Flowable.defer(
                                    () ->
                                            current.generateContent(
                                                    request.toBuilder()
                                                            .model(current.model())
                                                            .build(),
                                                    stream))
                            .doOnNext(response -> emitted[0] = true)
                            .onErrorResumeNext(
                                    error -> {
                                        if (emitted[0] || index + 1 == models.size()) {
                                            return Flowable.error(error);
                                        }
                                        return attempt(request, stream, index + 1);
                                    });
                });
    }

    @Override
    public BaseLlmConnection connect(LlmRequest request) {
        throw new UnsupportedOperationException("Model fallback supports generateContent only");
    }

    /** Closes each configured model once, including when an earlier close fails. */
    @Override
    public void close() throws Exception {
        Set<BaseLlm> closed = Collections.newSetFromMap(new IdentityHashMap<>());
        Exception failure = null;
        for (BaseLlm model : models) {
            if (closed.add(model) && model instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception error) {
                    if (failure == null) {
                        failure = error;
                    } else if (failure != error) {
                        failure.addSuppressed(error);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
