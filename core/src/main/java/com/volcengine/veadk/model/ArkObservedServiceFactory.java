/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.model;

import com.volcengine.ark.runtime.interceptor.AuthenticationInterceptor;
import com.volcengine.ark.runtime.interceptor.BatchInterceptor;
import com.volcengine.ark.runtime.interceptor.EncryptionInterceptor;
import com.volcengine.ark.runtime.interceptor.RequestIdInterceptor;
import com.volcengine.ark.runtime.interceptor.RetryInterceptor;
import com.volcengine.ark.runtime.service.ArkApi;
import com.volcengine.ark.runtime.service.ArkBaseService;
import com.volcengine.ark.runtime.service.ArkService;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

/** Uses official Ark components; the extra interceptor only observes bytes already read by SDK. */
final class ArkObservedServiceFactory implements Interceptor {
    static final String HEADER = "X-Veadk-Internal-Observation";
    static final long MAX_LINE_BYTES = 1024 * 1024;
    private final Map<String, ArkStreamingObservation> pending = new ConcurrentHashMap<>();

    ArkService create(ArkLlmConfig config, Dispatcher dispatcher, ConnectionPool pool) {
        OkHttpClient client = client(config, dispatcher, pool);
        String baseUrl = config.getApiBase();
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        return new ArkService(
                ArkService.defaultRetrofit(client, ArkService.defaultObjectMapper(), baseUrl, null)
                        .create(ArkApi.class),
                dispatcher.executorService());
    }

    OkHttpClient client(ArkLlmConfig config, Dispatcher dispatcher, ConnectionPool pool) {
        String baseUrl = config.getApiBase();
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        // Match ArkService.Builder 0.2.48, including encryption, batch and call timeout.
        return new OkHttpClient.Builder()
                .addInterceptor(this)
                .addInterceptor(new AuthenticationInterceptor(config.getApiKey()))
                .addInterceptor(new EncryptionInterceptor(config.getApiKey(), baseUrl))
                .addInterceptor(new RequestIdInterceptor())
                .addInterceptor(new RetryInterceptor(ArkBaseService.DEFAULT_RETRY_TIMES))
                .addInterceptor(new BatchInterceptor())
                .dispatcher(dispatcher)
                .connectionPool(pool)
                .readTimeout(ArkBaseService.DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .callTimeout(ArkBaseService.DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .connectTimeout(ArkBaseService.DEFAULT_CONNECT_TIMEOUT)
                .build();
    }

    String register(ArkStreamingObservation observation) {
        try {
            String key = UUID.randomUUID().toString();
            pending.put(key, observation);
            return key;
        } catch (RuntimeException ignored) {
            observation.invalidate();
            return null;
        }
    }

    void forget(String key) {
        if (key != null) pending.remove(key);
    }

    int pendingCount() {
        return pending.size();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        String key = chain.request().header(HEADER);
        ArkStreamingObservation observation = key == null ? null : pending.remove(key);
        // This correlation value must never reach authentication, tracing, retries or the network.
        var request = chain.request().newBuilder().removeHeader(HEADER).build();
        Response response = chain.proceed(request);
        if (observation == null) return response;
        try {
            if (!response.isSuccessful()
                    || response.body() == null
                    || response.cacheResponse() != null
                    || response.header("Age") != null
                    || !"text/event-stream"
                            .equalsIgnoreCase(
                                    response.body().contentType() == null
                                            ? null
                                            : response.body().contentType().type()
                                                    + "/"
                                                    + response.body().contentType().subtype())) {
                observation.invalidate();
                return response;
            }
            observation.attach();
            return response.newBuilder().body(observeBody(response.body(), observation)).build();
        } catch (RuntimeException ignored) {
            observation.invalidate();
            return response;
        }
    }

    static ResponseBody observeBody(ResponseBody delegate, ArkStreamingObservation observation) {
        BufferedSource source =
                Okio.buffer(
                        new ForwardingSource(delegate.source()) {
                            private final Buffer line = new Buffer();
                            private boolean disabled;

                            @Override
                            public long read(Buffer sink, long byteCount) throws IOException {
                                long offset = sink.size();
                                final long count;
                                try {
                                    count = super.read(sink, byteCount);
                                } catch (IOException | RuntimeException failure) {
                                    observation.invalidate();
                                    throw failure;
                                }
                                if (count <= 0 || disabled) return count;
                                try {
                                    Buffer copy = new Buffer();
                                    sink.copyTo(copy, offset, count);
                                    while (!copy.exhausted()) {
                                        byte next = copy.readByte();
                                        if (next == '\n') {
                                            String text = line.readUtf8();
                                            if (text.endsWith("\r"))
                                                text = text.substring(0, text.length() - 1);
                                            if (text.startsWith("data:")) {
                                                String data = text.substring(5).trim();
                                                if (!data.startsWith("[DONE]"))
                                                    observation.raw(data);
                                            } else if (!text.isEmpty()
                                                    && !text.startsWith("event:")) {
                                                observation.invalidate();
                                            }
                                        } else if (line.size() < MAX_LINE_BYTES) {
                                            line.writeByte(next);
                                        } else {
                                            disabled = true;
                                            line.clear();
                                            observation.invalidate();
                                            break;
                                        }
                                    }
                                } catch (RuntimeException ignored) {
                                    disabled = true;
                                    line.clear();
                                    observation.invalidate();
                                }
                                return count;
                            }

                            @Override
                            public void close() throws IOException {
                                line.clear();
                                try {
                                    super.close();
                                } catch (IOException | RuntimeException failure) {
                                    observation.invalidate();
                                    throw failure;
                                }
                                observation.closeSucceeded();
                            }
                        });
        return new ResponseBody() {
            @Override
            public MediaType contentType() {
                return delegate.contentType();
            }

            @Override
            public long contentLength() {
                return delegate.contentLength();
            }

            @Override
            public BufferedSource source() {
                return source;
            }
        };
    }
}
