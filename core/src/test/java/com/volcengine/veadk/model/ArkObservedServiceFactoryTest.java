/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.model;

import static com.volcengine.veadk.model.ArkStreamingObservationTest.delta;
import static com.volcengine.veadk.model.ArkStreamingObservationTest.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChunk;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.veadk.trace.StreamingTpotRecorder;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.junit.jupiter.api.Test;
import retrofit2.Call;
import retrofit2.Callback;

class ArkObservedServiceFactoryTest {
    private static final MediaType SSE = MediaType.get("text/event-stream; charset=utf-8");

    static String stream() {
        return "data: "
                + delta("{\"content\":\"a\"}", null)
                + "\n\n"
                + "data: "
                + delta("{\"content\":\"b\"}", "stop")
                + "\n\n"
                + "data: "
                + usage("100", "")
                + "\n\n"
                + "data: [DONE]\n\n";
    }

    @Test
    void officialSdkCompletesBeforeCloseAndRecordsOnlyAfterSuccessfulClose() {
        for (boolean closeFailure : List.of(false, true)) {
            StreamingTpotRecorder recorder = mock(StreamingTpotRecorder.class);
            AtomicLong clock = new AtomicLong();
            ArkStreamingObservation observation =
                    new ArkStreamingObservation(recorder, () -> clock.getAndAdd(990_000_000));
            observation.attach();
            BufferedSource source =
                    Okio.buffer(
                            new ForwardingSource(new Buffer().writeUtf8(stream())) {
                                @Override
                                public void close() throws IOException {
                                    // onComplete alone must not publish a sample, even though it
                                    // precedes cleanup.
                                    verify(recorder, never()).finish(anyDouble());
                                    if (closeFailure)
                                        throw new IOException("fixture close failure");
                                    super.close();
                                }
                            });
            ResponseBody body = ArkObservedServiceFactory.observeBody(body(source), observation);
            Call<ResponseBody> call = call(body);
            var subscriber =
                    ArkService.stream(call, ChatCompletionChunk.class)
                            .doOnNext(observation::observe)
                            .doOnComplete(observation::complete)
                            .doOnError(ignored -> observation.invalidate())
                            .test();
            subscriber.assertComplete().assertNoErrors().assertValueCount(3);
            assertThat(subscriber.values().get(0).getChoices().get(0).getMessage().getContent())
                    .isEqualTo("a");
            assertThat(subscriber.values().get(2).getUsage().getCompletionTokens()).isEqualTo(100);
            if (closeFailure) verify(recorder, never()).finish(anyDouble());
            else verify(recorder).finish(0.01);
        }
    }

    @Test
    void sourceReadFailurePreservesTheOriginalSdkError() {
        var fixture = new ArkStreamingObservationTest.Fixture();
        IOException error = new IOException("fixture read failure");
        BufferedSource source =
                Okio.buffer(
                        new ForwardingSource(new Buffer()) {
                            @Override
                            public long read(Buffer sink, long count) throws IOException {
                                throw error;
                            }
                        });
        ArkService.stream(
                        call(
                                ArkObservedServiceFactory.observeBody(
                                        body(source), fixture.observation)),
                        ChatCompletionChunk.class)
                .doOnNext(fixture.observation::observe)
                .doOnComplete(fixture.observation::complete)
                .test()
                .assertError(error);
        verify(fixture.recorder, never()).finish(anyDouble());
    }

    @Test
    void sideChannelIsByteTransparentAcrossSingleByteReadsAndCrLf() throws IOException {
        String text = stream().replace("\n", "\r\n");
        var fixture = new ArkStreamingObservationTest.Fixture();
        var source =
                Okio.buffer(
                        new ForwardingSource(new Buffer().writeUtf8(text)) {
                            @Override
                            public long read(Buffer sink, long count) throws IOException {
                                return super.read(sink, Math.min(count, 1));
                            }
                        });
        ResponseBody wrapped =
                ArkObservedServiceFactory.observeBody(body(source), fixture.observation);
        assertThat(wrapped.string()).isEqualTo(text);
    }

    @Test
    void oversizedSideChannelDisablesTelemetryAndStillPassesAllBytes() throws IOException {
        String text =
                "data: " + "x".repeat((int) ArkObservedServiceFactory.MAX_LINE_BYTES + 1) + "\n\n";
        var fixture = new ArkStreamingObservationTest.Fixture();
        ResponseBody wrapped =
                ArkObservedServiceFactory.observeBody(
                        ResponseBody.create(SSE, text), fixture.observation);
        assertThat(wrapped.string()).isEqualTo(text);
        fixture.normal();
        fixture.omitted();
    }

    @Test
    void throwingCloseIsPreservedByTheSource() {
        var fixture = new ArkStreamingObservationTest.Fixture();
        IOException original = new IOException("fixture close failure");
        BufferedSource source =
                Okio.buffer(
                        new ForwardingSource(new Buffer()) {
                            @Override
                            public void close() throws IOException {
                                throw original;
                            }
                        });
        ResponseBody wrapped =
                ArkObservedServiceFactory.observeBody(body(source), fixture.observation);
        assertThatThrownBy(() -> wrapped.source().close()).isSameAs(original);
        fixture.omitted();
    }

    @Test
    void internalCorrelationIsRemovedBeforeOfficialInterceptorsAndClaimedOnlyOnce()
            throws IOException {
        ArkObservedServiceFactory factory = new ArkObservedServiceFactory();
        ArkStreamingObservation observation = new ArkStreamingObservation("fixture");
        String key = factory.register(observation);
        Request request =
                new Request.Builder()
                        .url("http://localhost/fixture")
                        .header(ArkObservedServiceFactory.HEADER, key)
                        .build();
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        doAnswer(
                        invocation -> {
                            Request clean = invocation.getArgument(0);
                            assertThat(clean.header(ArkObservedServiceFactory.HEADER)).isNull();
                            assertThat(factory.pendingCount()).isZero();
                            return response(clean, ResponseBody.create(SSE, stream()));
                        })
                .when(chain)
                .proceed(any());
        try (Response response = factory.intercept(chain)) {
            assertThat(response.body().string()).isEqualTo(stream());
        }
        factory.forget(key);
        assertThat(factory.pendingCount()).isZero();
    }

    @Test
    void unclaimedRegistrationsAreRemovedOnCancellation() {
        ArkObservedServiceFactory factory = new ArkObservedServiceFactory();
        String key = factory.register(new ArkStreamingObservation("fixture"));
        assertThat(factory.pendingCount()).isEqualTo(1);
        factory.forget(key);
        factory.forget(key);
        assertThat(factory.pendingCount()).isZero();
    }

    @Test
    void cacheOrUnknownResponseTypeNeverAttachesTrustedCleanup() throws IOException {
        for (boolean cached : List.of(false, true)) {
            var fixture = new ArkStreamingObservationTest.Fixture();
            // Use a fresh state because a fixture attaches its own transport by default.
            ArkStreamingObservation observation =
                    new ArkStreamingObservation(fixture.recorder, fixture.clock::get);
            ArkObservedServiceFactory factory = new ArkObservedServiceFactory();
            Request request =
                    new Request.Builder()
                            .url("http://localhost/fixture")
                            .header(ArkObservedServiceFactory.HEADER, factory.register(observation))
                            .build();
            Interceptor.Chain chain = mock(Interceptor.Chain.class);
            when(chain.request()).thenReturn(request);
            Response.Builder reply =
                    response(
                                    request,
                                    ResponseBody.create(
                                            cached ? SSE : MediaType.get("application/json"),
                                            stream()))
                            .newBuilder();
            if (cached) reply.cacheResponse(response(request, null));
            when(chain.proceed(any())).thenReturn(reply.build());
            try (Response ignored = factory.intercept(chain)) {
                observation.complete();
                observation.closeSucceeded();
            }
            verify(fixture.recorder, never()).finish(anyDouble());
        }
    }

    @Test
    void officialBuilderDefaultsAndResourcesArePreserved() {
        Dispatcher dispatcher = new Dispatcher();
        ConnectionPool pool = new ConnectionPool(5, 1, java.util.concurrent.TimeUnit.SECONDS);
        ArkObservedServiceFactory factory = new ArkObservedServiceFactory();
        OkHttpClient client =
                factory.client(
                        ArkLlmConfig.builder().modelName("fixture").apiKey("fixture-key").build(),
                        dispatcher,
                        pool);
        assertThat(client.dispatcher()).isSameAs(dispatcher);
        assertThat(client.connectionPool()).isSameAs(pool);
        assertThat(client.readTimeoutMillis()).isEqualTo(600_000);
        assertThat(client.callTimeoutMillis()).isEqualTo(600_000);
        assertThat(client.connectTimeoutMillis()).isEqualTo(60_000);
        assertThat(client.interceptors().stream().map(i -> i.getClass().getSimpleName()).toList())
                .containsExactly(
                        "ArkObservedServiceFactory",
                        "AuthenticationInterceptor",
                        "EncryptionInterceptor",
                        "RequestIdInterceptor",
                        "RetryInterceptor",
                        "BatchInterceptor");
        dispatcher.executorService().shutdownNow();
    }

    private static ResponseBody body(BufferedSource source) {
        return new ResponseBody() {
            @Override
            public MediaType contentType() {
                return SSE;
            }

            @Override
            public long contentLength() {
                return -1;
            }

            @Override
            public BufferedSource source() {
                return source;
            }
        };
    }

    private static Response response(Request request, ResponseBody body) {
        Response.Builder builder =
                new Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK");
        if (body != null) builder.body(body);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static Call<ResponseBody> call(ResponseBody body) {
        Call<ResponseBody> call = mock(Call.class);
        doAnswer(
                        invocation -> {
                            Callback<ResponseBody> callback = invocation.getArgument(0);
                            callback.onResponse(call, retrofit2.Response.success(body));
                            return null;
                        })
                .when(call)
                .enqueue(any());
        return call;
    }
}
