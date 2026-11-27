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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.sun.net.httpserver.HttpServer;
import com.volcengine.ark.runtime.model.responses.event.outputtext.OutputTextDeltaEvent;
import com.volcengine.ark.runtime.model.responses.event.response.ResponseCreatedEvent;
import com.volcengine.ark.runtime.model.responses.event.response.ResponseFailedEvent;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;
import com.volcengine.ark.runtime.service.ArkService;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.PublishProcessor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelResponseIdTest {
    private final List<SpanData> spans = new CopyOnWriteArrayList<>();
    private SdkTracerProvider provider;
    private Tracer tracer;
    private HttpServer server;
    private java.util.concurrent.ExecutorService httpExecutor;
    private java.util.function.Function<String, Wire> routing;
    private java.util.concurrent.CountDownLatch releaseStream;
    private final AtomicInteger generatedRequestIds = new AtomicInteger();
    private final ConcurrentLinkedQueue<Wire> replies = new ConcurrentLinkedQueue<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        provider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(
                                SimpleSpanProcessor.builder(
                                                new SpanExporter() {
                                                    public CompletableResultCode export(
                                                            Collection<SpanData> batch) {
                                                        synchronized (spans) {
                                                            spans.addAll(batch);
                                                            spans.notifyAll();
                                                        }
                                                        return CompletableResultCode.ofSuccess();
                                                    }

                                                    public CompletableResultCode flush() {
                                                        return CompletableResultCode.ofSuccess();
                                                    }

                                                    public CompletableResultCode shutdown() {
                                                        return CompletableResultCode.ofSuccess();
                                                    }
                                                })
                                        .build())
                        .build();
        tracer = provider.get("response-id-test");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpExecutor = java.util.concurrent.Executors.newFixedThreadPool(2);
        server.setExecutor(httpExecutor);
        server.createContext(
                "/",
                exchange -> {
                    String payload =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    requests.add(payload);
                    if (exchange.getRequestHeaders().getFirst("X-Client-Request-Id") != null)
                        generatedRequestIds.incrementAndGet();
                    Wire wire = routing == null ? replies.remove() : routing.apply(payload);
                    if (wire.status == 0) {
                        exchange.close();
                        return;
                    }
                    if (wire.header != null)
                        exchange.getResponseHeaders().set("X-ReQuEsT-Id", wire.header);
                    exchange.getResponseHeaders()
                            .set(
                                    "Content-Type",
                                    wire.stream ? "text/event-stream" : "application/json");
                    byte[] body = wire.body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(wire.status, body.length);
                    try (var output = exchange.getResponseBody()) {
                        if (releaseStream == null) output.write(body);
                        else {
                            int cut = wire.body.indexOf("\n\n") + 2;
                            byte[] initial =
                                    wire.body.substring(0, cut).getBytes(StandardCharsets.UTF_8);
                            output.write(initial);
                            output.flush();
                            try {
                                if (!releaseStream.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                    throw new java.io.IOException("Stream fixture timed out");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new java.io.IOException(interrupted);
                            }
                            output.write(body, initial.length, body.length - initial.length);
                        }
                    }
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void cleanup() {
        if (releaseStream != null) releaseStream.countDown();
        server.stop(0);
        httpExecutor.shutdownNow();
        provider.close();
    }

    private static LlmRequest request() {
        return LlmRequest.builder()
                .model("fixture-model")
                .contents(
                        List.of(
                                Content.builder()
                                        .role("user")
                                        .parts(Part.fromText("hello"))
                                        .build()))
                .build();
    }

    private ArkLlm ark() {
        return new ArkLlm(
                ArkLlmConfig.builder()
                        .modelName("fixture-model")
                        .apiKey("fixture-key")
                        .apiBase(baseUrl())
                        .build());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/custom/";
    }

    private Flowable<LlmResponse> owned(Span owner, BaseLlm model, boolean stream) {
        try (Scope ignored = owner.makeCurrent()) {
            return model.generateContent(request(), stream).doFinally(owner::end);
        }
    }

    private String id(Span owner) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        synchronized (spans) {
            while (true) {
                var data =
                        spans.stream()
                                .filter(
                                        span ->
                                                span.getSpanId()
                                                        .equals(owner.getSpanContext().getSpanId()))
                                .findFirst();
                if (data.isPresent())
                    return data.get()
                            .getAttributes()
                            .get(AttributeKey.stringKey("gen_ai.response.id"));
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new AssertionError("Owner span was not exported");
                try {
                    java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(spans, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }
    }

    private static String body(String id) {
        return "{"
                + (id == null ? "" : "\"id\":\"" + id + "\",")
                + "\"model\":\"fixture-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello"
                + " fixture\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}";
    }

    private static String sse(String id) {
        String prefix = id == null ? "" : "\"id\":\"" + id + "\",";
        return "data: {"
                + prefix
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hello"
                + " fixture\"}}]}\n\n"
                + "data: {"
                + prefix
                + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\n"
                + "data: [DONE]\n\n";
    }

    private record Wire(int status, String header, String body, boolean stream) {}

    @Test
    void arkHeaderPriorityAndBodyProvenanceInBothModes() {
        for (boolean stream : List.of(false, true)) {
            for (String[] pair :
                    new String[][] {
                        {"header-A", "body-B"},
                        {"header-only", null},
                        {null, "body-only"},
                        {"", "body-fallback"},
                        {"   ", "body-fallback"},
                        {null, "5f52cff9-4c6f-4cf4-b2ea-f539170178a1"},
                        {null, null},
                        {null, ""},
                        {null, "   "}
                    }) {
                replies.add(new Wire(200, pair[0], stream ? sse(pair[1]) : body(pair[1]), stream));
                Span owner = tracer.spanBuilder("call_llm").startSpan();
                try (ArkLlm model = ark()) {
                    List<LlmResponse> output = owned(owner, model, stream).toList().blockingGet();
                    LlmResponse last = output.get(output.size() - 1);
                    assertThat(last.content().orElseThrow().parts().orElseThrow().get(0).text())
                            .contains("hello fixture");
                    assertThat(last.usageMetadata().orElseThrow().totalTokenCount()).contains(5);
                    assertThat(last.customMetadata()).isEmpty();
                    assertThat(id(owner))
                            .isEqualTo(
                                    ModelResponseId.valid(pair[0])
                                            ? pair[0]
                                            : ModelResponseId.valid(pair[1]) ? pair[1] : null);
                }
            }
        }
        assertThat(requests).hasSize(18);
        assertThat(generatedRequestIds.get()).isEqualTo(18);
    }

    @Test
    void sdkRetryUsesFinalResponseAndDoesNotKeepFailedHeader() {
        for (boolean stream : List.of(false, true)) {
            for (String finalId : new String[] {"final-id", null}) {
                replies.add(
                        new Wire(
                                500,
                                "failed-id",
                                "{\"error\":{\"code\":\"InternalError\",\"message\":\"retry\"}}",
                                false));
                replies.add(new Wire(200, finalId, stream ? sse(null) : body(null), stream));
                Span owner = tracer.spanBuilder("call_llm").startSpan();
                try (ArkLlm model = ark()) {
                    owned(owner, model, stream).toList().blockingGet();
                    assertThat(id(owner)).isEqualTo(finalId);
                }
            }
        }
        assertThat(requests).hasSize(8);
    }

    @Test
    void sdkRateLimitAndNetworkRetryUseOnlyFinalId() {
        for (int status : List.of(429, 0)) {
            replies.add(
                    new Wire(
                            status,
                            "failed-id",
                            "{\"error\":{\"code\":\"TooManyRequests\",\"message\":\"retry\"}}",
                            false));
            replies.add(new Wire(200, null, body("final-body"), false));
            Span owner = tracer.spanBuilder("call_llm").startSpan();
            try (ArkLlm model = ark()) {
                owned(owner, model, false).toList().blockingGet();
                assertThat(id(owner)).isEqualTo("final-body");
            }
        }
    }

    @Test
    void finalHttpFailureRetainsItsActualResponseHeader() {
        replies.add(
                new Wire(
                        400,
                        "failed-final",
                        "{\"error\":{\"code\":\"BadRequest\",\"message\":\"rejected\"}}",
                        false));
        Span owner = tracer.spanBuilder("call_llm").startSpan();
        try (ArkLlm model = ark()) {
            assertThatThrownBy(() -> owned(owner, model, false).toList().blockingGet())
                    .isInstanceOf(RuntimeException.class);
            assertThat(id(owner)).isEqualTo("failed-final");
        }
    }

    @Test
    void fallbackDropsFailedAttemptEvenWhenFinalModelIsUninstrumented() throws Exception {
        for (String finalId : new String[] {"fallback-final", null}) {
            Span owner = tracer.spanBuilder("call_llm").startSpan();
            BaseLlm failed = stub("primary", "failed-id", true);
            BaseLlm last =
                    finalId == null
                            ? new BaseLlm("plain") {
                                public Flowable<LlmResponse> generateContent(
                                        LlmRequest request, boolean stream) {
                                    return Flowable.just(LlmResponse.builder().build());
                                }

                                public BaseLlmConnection connect(LlmRequest request) {
                                    throw new UnsupportedOperationException();
                                }
                            }
                            : stub("fallback", finalId, false);
            try (FallbackLlm model = new FallbackLlm(failed, List.of(last))) {
                owned(owner, model, true).toList().blockingGet();
                assertThat(id(owner)).isEqualTo(finalId);
            }
        }
    }

    @Test
    void realArkToOpenAiFallbackPublishesOnlyFinalProviderId() throws Exception {
        for (String finalId : new String[] {"openai-final", null}) {
            replies.add(
                    new Wire(
                            400,
                            "failed-ark-header",
                            "{\"error\":{\"code\":\"BadRequest\",\"message\":\"fallback\"}}",
                            false));
            replies.add(new Wire(200, "not-an-openai-response-id", body(finalId), false));
            Span owner = tracer.spanBuilder("call_llm").startSpan();
            var backup =
                    new OpenAiCompatibleLlm(
                            OpenAiCompatibleLlmConfig.builder()
                                    .modelName("fixture-model")
                                    .apiKey("fixture-key")
                                    .baseUrl(baseUrl())
                                    .build());
            try (FallbackLlm model = new FallbackLlm(ark(), List.of(backup))) {
                assertThat(owned(owner, model, false).toList().blockingGet()).hasSize(1);
                assertThat(id(owner)).isEqualTo(finalId);
            }
        }
        assertThat(requests).hasSize(4);
    }

    private static BaseLlm stub(String name, String id, boolean fail) {
        return new BaseLlm(name) {
            public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
                return ModelResponseId.trace(
                        () -> {
                            ModelResponseId.beginAttempt().body(id);
                            return fail
                                    ? Flowable.error(new IllegalStateException("failure"))
                                    : Flowable.just(LlmResponse.builder().build());
                        });
            }

            public BaseLlmConnection connect(LlmRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void interleavedSubscriptionsCancellationAndConsumersKeepProducerOwnership() {
        PublishProcessor<LlmResponse> first = PublishProcessor.create();
        PublishProcessor<LlmResponse> second = PublishProcessor.create();
        List<ModelResponseId.Attempt> attempts = new ArrayList<>();
        Span ownerA = tracer.spanBuilder("call_llm-A").startSpan();
        Span ownerB = tracer.spanBuilder("call_llm-B").startSpan();
        Flowable<LlmResponse> a;
        Flowable<LlmResponse> b;
        try (Scope ignored = ownerA.makeCurrent()) {
            a =
                    ModelResponseId.trace(
                                    () -> {
                                        attempts.add(ModelResponseId.beginAttempt());
                                        return first;
                                    })
                            .doFinally(ownerA::end);
        }
        try (Scope ignored = ownerB.makeCurrent()) {
            b =
                    ModelResponseId.trace(
                                    () -> {
                                        attempts.add(ModelResponseId.beginAttempt());
                                        return second;
                                    })
                            .doFinally(ownerB::end);
        }
        Span consumer = tracer.spanBuilder("consumer").startSpan();
        try (Scope ignored = consumer.makeCurrent()) {
            var sa = a.test();
            var sb = b.test();
            attempts.get(1).body("B");
            attempts.get(0).header("A");
            sa.cancel();
            attempts.get(0).body("late-wrong");
            second.onError(new IllegalStateException("closed"));
            sb.assertError(IllegalStateException.class);
        }
        consumer.end();
        assertThat(id(ownerA)).isEqualTo("A");
        assertThat(id(ownerB)).isEqualTo("B");
        assertThat(id(consumer)).isNull();
    }

    @Test
    void sameArkInstanceInterleavesRealSdkStreamsAndCancelsOnlyItsOwner() {
        releaseStream = new java.util.concurrent.CountDownLatch(1);
        routing =
                payload -> {
                    String name = payload.contains("hello-A") ? "A" : "B";
                    return new Wire(
                            200,
                            "header-" + name,
                            sse("body-" + name)
                                    .replace(
                                            "hello fixture", "abcdefghijklmnopqrstuvwxyz123456789"),
                            true);
                };
        Span ownerA = tracer.spanBuilder("call_llm-A").startSpan();
        Span ownerB = tracer.spanBuilder("call_llm-B").startSpan();
        Span consumer = tracer.spanBuilder("consumer").startSpan();
        try (ArkLlm model = ark()) {
            Flowable<LlmResponse> a;
            Flowable<LlmResponse> b;
            try (Scope ignored = ownerA.makeCurrent()) {
                a =
                        model.generateContent(
                                        request().toBuilder()
                                                .contents(
                                                        List.of(
                                                                Content.builder()
                                                                        .role("user")
                                                                        .parts(
                                                                                Part.fromText(
                                                                                        "hello-A"))
                                                                        .build()))
                                                .build(),
                                        true)
                                .doFinally(ownerA::end);
            }
            try (Scope ignored = ownerB.makeCurrent()) {
                b =
                        model.generateContent(
                                        request().toBuilder()
                                                .contents(
                                                        List.of(
                                                                Content.builder()
                                                                        .role("user")
                                                                        .parts(
                                                                                Part.fromText(
                                                                                        "hello-B"))
                                                                        .build()))
                                                .build(),
                                        true)
                                .doFinally(ownerB::end);
            }
            try (Scope ignored = consumer.makeCurrent()) {
                var sa = a.test();
                var sb = b.test();
                sa.awaitCount(1).assertValueCount(1);
                sb.awaitCount(1).assertValueCount(1);
                sa.cancel();
                releaseStream.countDown();
                sb.awaitDone(5, java.util.concurrent.TimeUnit.SECONDS)
                        .assertComplete()
                        .assertNoErrors();
                assertThat(id(ownerA)).isEqualTo("header-A");
                assertThat(id(ownerB)).isEqualTo("header-B");
            }
        }
        consumer.end();
        assertThat(id(consumer)).isNull();
        assertThat(spans).hasSize(3);
    }

    @Test
    void nestedIndependentModelSpanDoesNotInheritAnotherOwnersAttempt() {
        Span outer = tracer.spanBuilder("call_llm-outer").startSpan();
        List<Span> nested = new ArrayList<>();
        Flowable<LlmResponse> result;
        try (Scope ignored = outer.makeCurrent()) {
            result =
                    stub("outer", "outer-id", false)
                            .generateContent(request(), false)
                            .doOnNext(
                                    response -> {
                                        Span inner =
                                                tracer.spanBuilder("call_llm-inner").startSpan();
                                        nested.add(inner);
                                        try (Scope child = inner.makeCurrent()) {
                                            stub("inner", "inner-id", false)
                                                    .generateContent(request(), false)
                                                    .doFinally(inner::end)
                                                    .toList()
                                                    .blockingGet();
                                        }
                                    })
                            .doFinally(outer::end);
        }
        result.toList().blockingGet();
        assertThat(id(outer)).isEqualTo("outer-id");
        assertThat(id(nested.get(0))).isEqualTo("inner-id");
    }

    @Test
    void resubscriptionGetsFreshAttemptAndRejectsOldCallbacks() {
        AtomicInteger subscriptions = new AtomicInteger();
        List<ModelResponseId.Attempt> attempts = new ArrayList<>();
        Span owner = tracer.spanBuilder("call_llm").startSpan();
        Flowable<LlmResponse> stream;
        try (Scope ignored = owner.makeCurrent()) {
            stream =
                    ModelResponseId.trace(
                            () -> {
                                ModelResponseId.Attempt attempt = ModelResponseId.beginAttempt();
                                attempts.add(attempt);
                                attempt.body("subscription-" + subscriptions.incrementAndGet());
                                if (attempts.size() > 1) attempts.get(0).header("late-old");
                                return Flowable.just(LlmResponse.builder().build());
                            });
        }
        stream.toList().blockingGet();
        stream.toList().blockingGet();
        owner.end();
        assertThat(id(owner)).isEqualTo("subscription-2");
    }

    @Test
    void responsesCreatedIdSurvivesCancellationBeforeAnyTextAndFailedIdSurvivesError() {
        ArkService service = mock(ArkService.class);
        ResponseObject raw = new ResponseObject();
        raw.setId("resp_original");
        ResponseCreatedEvent created = new ResponseCreatedEvent();
        created.setResponse(raw);
        OutputTextDeltaEvent delta = new OutputTextDeltaEvent();
        delta.setDelta("hello");
        when(service.streamResponse(any()))
                .thenReturn(
                        io.reactivex.Flowable.just(created, delta)
                                .concatWith(io.reactivex.Flowable.never()));
        Span owner = tracer.spanBuilder("call_llm").startSpan();
        Flowable<LlmResponse> stream;
        try (Scope ignored = owner.makeCurrent()) {
            stream =
                    new ArkResponsesAdapter(
                                    service,
                                    ArkLlmConfig.builder()
                                            .modelName("fixture")
                                            .apiKey("fixture")
                                            .api(ArkLlmConfig.Api.RESPONSES)
                                            .build())
                            .generate(request(), true)
                            .doFinally(owner::end);
        }
        stream.take(1).toList().blockingGet();
        assertThat(id(owner)).isEqualTo("resp_original");
        ResponseFailedEvent failed = new ResponseFailedEvent();
        failed.setResponse(raw);
        when(service.streamResponse(any())).thenReturn(io.reactivex.Flowable.just(failed));
        Span failedOwner = tracer.spanBuilder("call_llm").startSpan();
        try (Scope ignored = failedOwner.makeCurrent()) {
            assertThatThrownBy(
                            () ->
                                    new ArkResponsesAdapter(
                                                    service,
                                                    ArkLlmConfig.builder()
                                                            .modelName("fixture")
                                                            .apiKey("fixture")
                                                            .build())
                                            .generate(request(), true)
                                            .doFinally(failedOwner::end)
                                            .toList()
                                            .blockingGet())
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(id(failedOwner)).isEqualTo("resp_original");
    }

    @Test
    void responsesCancelAfterCreatedEventWithNoEmittedContent() {
        ArkService service = mock(ArkService.class);
        ResponseObject raw = new ResponseObject();
        raw.setId("resp_before_text");
        ResponseCreatedEvent created = new ResponseCreatedEvent();
        created.setResponse(raw);
        when(service.streamResponse(any()))
                .thenReturn(
                        io.reactivex.Flowable
                                .<com.volcengine.ark.runtime.model.responses.event.StreamEvent>just(
                                        created)
                                .concatWith(io.reactivex.Flowable.never()));
        Span owner = tracer.spanBuilder("call_llm").startSpan();
        Flowable<LlmResponse> result;
        try (Scope ignored = owner.makeCurrent()) {
            result =
                    new ArkResponsesAdapter(
                                    service,
                                    ArkLlmConfig.builder()
                                            .modelName("fixture")
                                            .apiKey("fixture")
                                            .build())
                            .generate(request(), true)
                            .doFinally(owner::end);
        }
        var subscriber = result.test();
        subscriber.assertNoValues();
        subscriber.cancel();
        assertThat(id(owner)).isEqualTo("resp_before_text");
    }

    @Test
    void openAiKeepsBodyIdAndNeverUsesArkResponseHeader() {
        for (boolean stream : List.of(false, true)) {
            for (String bodyId : new String[] {"openai-body", null, "", "   "}) {
                replies.add(
                        new Wire(200, "ark-header", stream ? sse(bodyId) : body(bodyId), stream));
                Span owner = tracer.spanBuilder("call_llm").startSpan();
                var model =
                        new OpenAiCompatibleLlm(
                                OpenAiCompatibleLlmConfig.builder()
                                        .modelName("fixture-model")
                                        .apiKey("fixture-key")
                                        .baseUrl(baseUrl())
                                        .build());
                List<LlmResponse> output = owned(owner, model, stream).toList().blockingGet();
                assertThat(output).isNotEmpty();
                assertThat(id(owner)).isEqualTo(ModelResponseId.valid(bodyId) ? bodyId : null);
            }
        }
    }

    @Test
    void openAiSuppressedToolOnlyIdIsUnavailableAtOfficialAdkBoundary() {
        // Dependency evidence for REQ-005, not a passing acceptance case: ADK 1.11.0
        // consumes this ID but emits no LlmResponse to the adapter before stream closure.
        String wire =
                "data:"
                    + " {\"id\":\"raw-tool-only-id\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"tool-1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]}}]}\n\n"
                    + "data: [DONE]\n\n";
        replies.add(new Wire(200, "unused-header", wire, true));
        Span owner = tracer.spanBuilder("call_llm").startSpan();
        var model =
                new OpenAiCompatibleLlm(
                        OpenAiCompatibleLlmConfig.builder()
                                .modelName("fixture-model")
                                .apiKey("fixture-key")
                                .baseUrl(baseUrl())
                                .build());
        List<LlmResponse> output = owned(owner, model, true).toList().blockingGet();
        assertThat(output).isEmpty();
        assertThat(id(owner)).isNull();
    }

    @Test
    void arkRawIdBeforeFilteredChunkSurvivesCancellationAndTruncatedStream() {
        String early = "data: {\"id\":\"early-raw-id\",\"choices\":[]}\n\n";
        String content =
                "data:"
                    + " {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"abcdefghijklmnopqrstuvwxyz123456789\"}}]}\n\n";
        for (boolean cancel : List.of(false, true)) {
            replies.add(new Wire(200, null, early + content, true));
            Span owner = tracer.spanBuilder("call_llm").startSpan();
            try (ArkLlm model = ark()) {
                Flowable<LlmResponse> result = owned(owner, model, true);
                if (cancel) assertThat(result.take(1).toList().blockingGet()).hasSize(1);
                else
                    assertThatThrownBy(() -> result.toList().blockingGet())
                            .isInstanceOf(IllegalStateException.class);
                assertThat(id(owner)).isEqualTo("early-raw-id");
            }
        }
    }

    private static String responseBody(String id) {
        return "{"
                + (id == null ? "" : "\"id\":\"" + id + "\",")
                + "\"model\":\"fixture-model\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"id\":\"message-1\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello"
                + " fixture\"}]}],\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5}}";
    }

    @Test
    void responsesUseOriginalBodyIdInBothModesAndIgnoreChatHeader() {
        for (boolean stream : List.of(false, true)) {
            for (String bodyId : new String[] {"resp_original", null}) {
                String json = responseBody(bodyId);
                String payload =
                        stream
                                ? "data: {\"type\":\"response.completed\",\"response\":"
                                        + json
                                        + "}\n\n"
                                : json;
                replies.add(new Wire(200, "chat-header", payload, stream));
                Span owner = tracer.spanBuilder("call_llm").startSpan();
                try (ArkLlm model =
                        new ArkLlm(
                                ArkLlmConfig.builder()
                                        .modelName("fixture-model")
                                        .apiKey("fixture-key")
                                        .apiBase(baseUrl())
                                        .api(ArkLlmConfig.Api.RESPONSES)
                                        .build())) {
                    LlmResponse result = owned(owner, model, stream).toList().blockingGet().get(0);
                    assertThat(id(owner)).isEqualTo(bodyId);
                    assertThat(ArkResponsesSession.responseId(result))
                            .isEqualTo(java.util.Optional.ofNullable(bodyId));
                }
            }
        }
    }

    @Test
    void responsesExpiredContinuationRetryDoesNotKeepFailedResponseId() {
        replies.add(new Wire(200, null, responseBody("resp_previous"), false));
        try (ArkLlm model =
                new ArkLlm(
                        ArkLlmConfig.builder()
                                .modelName("fixture-model")
                                .apiKey("fixture-key")
                                .apiBase(baseUrl())
                                .api(ArkLlmConfig.Api.RESPONSES)
                                .build())) {
            LlmResponse previous = model.generateContent(request(), false).blockingFirst();
            List<Content> history = new ArrayList<>(request().contents());
            history.add(previous.content().orElseThrow());
            history.add(Content.builder().role("user").parts(Part.fromText("next")).build());
            LlmRequest continued =
                    ArkResponsesSession.continueFrom(
                            request().toBuilder().contents(history).build(), previous);
            replies.add(
                    new Wire(
                            400,
                            "failed-chat-header",
                            "{\"error\":{\"code\":\"InvalidParameter.PreviousResponseNotFound\",\"message\":\"expired\"}}",
                            false));
            replies.add(new Wire(200, "final-chat-header", responseBody(null), false));
            Span owner = tracer.spanBuilder("call_llm").startSpan();
            Flowable<LlmResponse> result;
            try (Scope ignored = owner.makeCurrent()) {
                result = model.generateContent(continued, false).doFinally(owner::end);
            }
            result.toList().blockingGet();
            assertThat(id(owner)).isNull();
            assertThat(requests.get(1)).contains("previous_response_id");
            assertThat(requests.get(2)).doesNotContain("previous_response_id");
        }
    }

    @Test
    void tracingOffDoesNotCreateObservationOrChangeModelOutput() {
        replies.add(new Wire(200, "real-id", body("body-id"), false));
        try (ArkLlm model = ark()) {
            LlmResponse result = model.generateContent(request(), false).blockingFirst();
            assertThat(result.content().orElseThrow().parts().orElseThrow().get(0).text())
                    .contains("hello fixture");
        }
        assertThat(spans).isEmpty();
    }
}
