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

import com.google.adk.models.LlmResponse;
import com.google.adk.telemetry.Tracing;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.functions.Supplier;

/** Response provenance scoped to one subscription and its producing model span. */
final class ModelResponseId {
    private static final ContextKey<ModelResponseId> KEY =
            ContextKey.named("veadk-model-response-id");
    private final Span owner;
    private Attempt current;
    private boolean finished;

    private ModelResponseId(Span owner) {
        this.owner = owner;
    }

    static Flowable<LlmResponse> trace(Supplier<Flowable<LlmResponse>> source) {
        Context producer = Context.current();
        return Flowable.defer(
                () -> {
                    ModelResponseId inherited = producer.get(KEY);
                    if (inherited != null && !inherited.belongsTo(Span.fromContext(producer))) {
                        inherited = null;
                    }
                    boolean root = inherited == null;
                    ModelResponseId call =
                            root ? new ModelResponseId(Span.fromContext(producer)) : inherited;
                    Flowable<LlmResponse> result =
                            Flowable.defer(source)
                                    .compose(Tracing.withContext(producer.with(KEY, call)));
                    // Publish before ADK's outer doFinally ends the span, including cancellation.
                    return root
                            ? result.doOnComplete(call::finish)
                                    .doOnError(error -> call.finish())
                                    .doOnCancel(call::finish)
                            : result;
                });
    }

    static Attempt beginAttempt() {
        ModelResponseId call = Context.current().get(KEY);
        if (call == null) {
            call = new ModelResponseId(Span.getInvalid());
        }
        synchronized (call) {
            Attempt attempt = new Attempt(call);
            if (!call.finished) call.current = attempt;
            return attempt;
        }
    }

    private synchronized boolean belongsTo(Span span) {
        return !finished && owner.getSpanContext().equals(span.getSpanContext());
    }

    private synchronized void finish() {
        if (finished) return;
        finished = true;
        if (current != null && owner.isRecording()) {
            String id = valid(current.header) ? current.header : current.body;
            if (valid(id)) owner.setAttribute("gen_ai.response.id", id);
        }
    }

    static boolean valid(String id) {
        return id != null && !id.isBlank();
    }

    static final class Attempt {
        private final ModelResponseId call;
        private String header;
        private String body;

        private Attempt(ModelResponseId call) {
            this.call = call;
        }

        boolean recording() {
            return call.owner.isRecording();
        }

        void reset() {
            synchronized (call) {
                if (call.finished || call.current != this) return;
                header = null;
                body = null;
            }
        }

        void header(String id) {
            synchronized (call) {
                if (!call.finished && call.current == this && valid(id)) header = id;
            }
        }

        void body(String id) {
            synchronized (call) {
                if (!call.finished && call.current == this && valid(id)) body = id;
            }
        }
    }
}
