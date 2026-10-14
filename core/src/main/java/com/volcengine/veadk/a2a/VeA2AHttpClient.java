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
package com.volcengine.veadk.a2a;

import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.http.A2AHttpResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** A2A SDK HTTP adapter with bounded connect and request timeouts. */
public final class VeA2AHttpClient implements A2AHttpClient {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(600);

    private final HttpClient client;
    private final Duration requestTimeout;

    public VeA2AHttpClient() {
        this(DEFAULT_TIMEOUT);
    }

    public VeA2AHttpClient(Duration timeout) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(requirePositive(timeout))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                timeout);
    }

    public VeA2AHttpClient(HttpClient client, Duration requestTimeout) {
        this.client = Objects.requireNonNull(client, "client must be set.");
        this.requestTimeout = requirePositive(requestTimeout);
    }

    @Override
    public GetBuilder createGet() {
        return new Get();
    }

    @Override
    public PostBuilder createPost() {
        return new Post();
    }

    @Override
    public DeleteBuilder createDelete() {
        return new Delete();
    }

    private abstract class RequestBuilder<T extends RequestBuilder<T>> {
        private URI uri;
        private final Map<String, String> headers = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        final T self() {
            return (T) this;
        }

        T url(String url) {
            this.uri = URI.create(requireText(url, "url must be set."));
            return self();
        }

        T addHeaders(Map<String, String> values) {
            if (values != null) {
                values.forEach(this::addHeader);
            }
            return self();
        }

        T addHeader(String name, String value) {
            headers.put(
                    requireText(name, "header name must be set."), Objects.requireNonNull(value));
            return self();
        }

        final HttpRequest.Builder request() {
            if (uri == null) {
                throw new IllegalStateException("url must be set before sending an A2A request.");
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(requestTimeout);
            headers.forEach(request::header);
            return request;
        }

        final A2AHttpResponse send(HttpRequest request) throws IOException, InterruptedException {
            HttpResponse<String> response =
                    client.send(
                            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        }

        final CompletableFuture<Void> sendSse(
                HttpRequest request,
                Consumer<String> messageConsumer,
                Consumer<Throwable> errorConsumer,
                Runnable completeRunnable) {
            Objects.requireNonNull(messageConsumer, "messageConsumer must be set.");
            Objects.requireNonNull(errorConsumer, "errorConsumer must be set.");
            Objects.requireNonNull(completeRunnable, "completeRunnable must be set.");
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                    .thenAcceptAsync(
                            response -> {
                                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                                    throw new IllegalStateException(
                                            "A2A streaming request failed with HTTP status "
                                                    + response.statusCode());
                                }
                                try (Stream<String> lines = response.body()) {
                                    lines.map(VeA2AHttpClient::sseData)
                                            .filter(value -> !value.isEmpty())
                                            .forEach(messageConsumer);
                                }
                            })
                    .whenComplete(
                            (unused, failure) -> {
                                if (failure == null) {
                                    completeRunnable.run();
                                } else {
                                    errorConsumer.accept(unwrap(failure));
                                }
                            });
        }
    }

    private final class Get extends RequestBuilder<Get> implements GetBuilder {
        @Override
        public Get url(String url) {
            return super.url(url);
        }

        @Override
        public Get addHeaders(Map<String, String> headers) {
            return super.addHeaders(headers);
        }

        @Override
        public Get addHeader(String name, String value) {
            return super.addHeader(name, value);
        }

        @Override
        public A2AHttpResponse get() throws IOException, InterruptedException {
            return send(request().GET().build());
        }

        @Override
        public CompletableFuture<Void> getAsyncSSE(
                Consumer<String> messageConsumer,
                Consumer<Throwable> errorConsumer,
                Runnable completeRunnable) {
            return sendSse(
                    request().header("Accept", "text/event-stream").GET().build(),
                    messageConsumer,
                    errorConsumer,
                    completeRunnable);
        }
    }

    private final class Post extends RequestBuilder<Post> implements PostBuilder {
        private String body = "";

        @Override
        public Post url(String url) {
            return super.url(url);
        }

        @Override
        public Post addHeaders(Map<String, String> headers) {
            return super.addHeaders(headers);
        }

        @Override
        public Post addHeader(String name, String value) {
            return super.addHeader(name, value);
        }

        @Override
        public Post body(String body) {
            this.body = Objects.requireNonNullElse(body, "");
            return this;
        }

        @Override
        public A2AHttpResponse post() throws IOException, InterruptedException {
            return send(request().POST(HttpRequest.BodyPublishers.ofString(body)).build());
        }

        @Override
        public CompletableFuture<Void> postAsyncSSE(
                Consumer<String> messageConsumer,
                Consumer<Throwable> errorConsumer,
                Runnable completeRunnable) {
            return sendSse(
                    request()
                            .header("Accept", "text/event-stream")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    messageConsumer,
                    errorConsumer,
                    completeRunnable);
        }
    }

    private final class Delete extends RequestBuilder<Delete> implements DeleteBuilder {
        @Override
        public Delete url(String url) {
            return super.url(url);
        }

        @Override
        public Delete addHeaders(Map<String, String> headers) {
            return super.addHeaders(headers);
        }

        @Override
        public Delete addHeader(String name, String value) {
            return super.addHeader(name, value);
        }

        @Override
        public A2AHttpResponse delete() throws IOException, InterruptedException {
            return send(request().DELETE().build());
        }
    }

    private record Response(int status, String body) implements A2AHttpResponse {
        @Override
        public boolean success() {
            return status >= 200 && status < 300;
        }
    }

    private static String sseData(String line) {
        if (line == null) {
            return "";
        }
        return line.startsWith("data:") ? line.substring(5).strip() : line.strip();
    }

    private static Throwable unwrap(Throwable throwable) {
        return throwable.getCause() == null ? throwable : throwable.getCause();
    }

    private static Duration requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "timeout must be set.");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive.");
        }
        return duration;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
