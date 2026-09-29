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
package com.volcengine.veadk.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class VikingApiKeyHttpClientTest {

    private static final String SECRET_MARKER = "unique-secret-marker";

    @Test
    void postBuildsFixedBearerRequestAndReturnsJson() throws Exception {
        CapturingTransport transport = new CapturingTransport(200, "{\"code\":0,\"data\":{}}");
        VikingApiKeyHttpClient client = new VikingApiKeyHttpClient(SECRET_MARKER, transport);

        assertThat(
                        client.post(
                                        "search memory",
                                        VikingApiKeyHttpClient.MEMORY_SEARCH_PATH,
                                        Map.of("query", "hello"))
                                .path("code")
                                .asInt())
                .isZero();
        assertThat(transport.request.method()).isEqualTo("POST");
        assertThat(transport.request.uri().toString())
                .isEqualTo("https://api-knowledgebase.mlp.cn-beijing.volces.com/api/memory/search");
        assertThat(transport.request.timeout()).contains(Duration.ofSeconds(30));
        assertThat(transport.request.headers().firstValue("Accept")).contains("application/json");
        assertThat(transport.request.headers().firstValue("Content-Type"))
                .contains("application/json");
        assertThat(transport.request.headers().firstValue("Authorization"))
                .contains("Bearer " + SECRET_MARKER);
        assertThat(readBody(transport.request)).contains("\"query\":\"hello\"");
        assertThat(transport.callCount).isEqualTo(1);
    }

    @Test
    void postRejectsUnsupportedPathBeforeTransport() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        VikingApiKeyHttpClient client = new VikingApiKeyHttpClient(SECRET_MARKER, transport);

        assertThatThrownBy(() -> client.post("bad", "/not-allowed", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported Viking API Key path")
                .hasMessageNotContaining(SECRET_MARKER);
        assertThat(transport.callCount).isZero();
    }

    @Test
    void postReportsHttpAndBusinessFailuresWithoutSecretOrBody() {
        VikingApiKeyHttpClient httpFailure =
                new VikingApiKeyHttpClient(
                        SECRET_MARKER,
                        new CapturingTransport(
                                403,
                                "{\"code\":1001,\"request_id\":\"request-1\",\"message\":\""
                                        + SECRET_MARKER
                                        + "\"}"));

        assertThatThrownBy(
                        () ->
                                httpFailure.post(
                                        "search memory",
                                        VikingApiKeyHttpClient.MEMORY_SEARCH_PATH,
                                        Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status=403")
                .hasMessageContaining("code=1001")
                .hasMessageContaining("requestId=request-1")
                .hasMessageNotContaining(SECRET_MARKER);

        VikingApiKeyHttpClient businessFailure =
                new VikingApiKeyHttpClient(
                        SECRET_MARKER,
                        new CapturingTransport(
                                200, "{\"code\":2002,\"request_id\":\"request-2\"}"));
        assertThatThrownBy(
                        () ->
                                businessFailure.post(
                                        "search knowledgebase",
                                        VikingApiKeyHttpClient.KNOWLEDGEBASE_SEARCH_PATH,
                                        Map.of()))
                .hasMessageContaining("code=2002")
                .hasMessageNotContaining(SECRET_MARKER);
    }

    @Test
    void postReportsMalformedAndEmptyResponses() {
        VikingApiKeyHttpClient malformed =
                new VikingApiKeyHttpClient(SECRET_MARKER, new CapturingTransport(200, "not-json"));
        assertThatThrownBy(
                        () ->
                                malformed.post(
                                        "add memory session",
                                        VikingApiKeyHttpClient.MEMORY_ADD_PATH,
                                        Map.of()))
                .hasMessageContaining("response parsing failed")
                .hasMessageNotContaining(SECRET_MARKER);

        VikingApiKeyHttpClient empty =
                new VikingApiKeyHttpClient(SECRET_MARKER, new CapturingTransport(200, " "));
        assertThatThrownBy(
                        () ->
                                empty.post(
                                        "add memory session",
                                        VikingApiKeyHttpClient.MEMORY_ADD_PATH,
                                        Map.of()))
                .hasMessageContaining("empty response");
    }

    @Test
    void postWrapsIoAndInterruptsWithoutLeakingSecret() {
        VikingApiKeyHttpClient ioFailure =
                new VikingApiKeyHttpClient(
                        SECRET_MARKER,
                        request -> {
                            throw new IOException(SECRET_MARKER);
                        });
        assertThatThrownBy(
                        () ->
                                ioFailure.post(
                                        "search memory",
                                        VikingApiKeyHttpClient.MEMORY_SEARCH_PATH,
                                        Map.of()))
                .hasMessageContaining("request failed")
                .hasMessageNotContaining(SECRET_MARKER);

        VikingApiKeyHttpClient interrupted =
                new VikingApiKeyHttpClient(
                        SECRET_MARKER,
                        request -> {
                            throw new InterruptedException(SECRET_MARKER);
                        });
        try {
            assertThatThrownBy(
                            () ->
                                    interrupted.post(
                                            "search memory",
                                            VikingApiKeyHttpClient.MEMORY_SEARCH_PATH,
                                            Map.of()))
                    .hasMessageContaining("request interrupted")
                    .hasMessageNotContaining(SECRET_MARKER);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void constructorRejectsBlankApiKey() {
        assertThatThrownBy(() -> new VikingApiKeyHttpClient(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String readBody(HttpRequest request) throws Exception {
        Optional<HttpRequest.BodyPublisher> publisher = request.bodyPublisher();
        if (publisher.isEmpty()) {
            return "";
        }
        BodyCaptureSubscriber subscriber = new BodyCaptureSubscriber();
        publisher.get().subscribe(subscriber);
        return subscriber.getBody();
    }

    private static class CapturingTransport implements VikingApiKeyHttpClient.VikingHttpTransport {
        private final int statusCode;
        private final String body;
        private HttpRequest request;
        private int callCount;

        private CapturingTransport(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public VikingApiKeyHttpClient.VikingHttpResponse send(HttpRequest request) {
            this.request = request;
            callCount++;
            return new VikingApiKeyHttpClient.VikingHttpResponse(statusCode, body);
        }
    }

    private static class BodyCaptureSubscriber implements Flow.Subscriber<ByteBuffer> {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CountDownLatch done = new CountDownLatch(1);
        private Throwable error;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            try {
                output.write(bytes);
            } catch (IOException e) {
                error = e;
            }
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
            done.countDown();
        }

        @Override
        public void onComplete() {
            done.countDown();
        }

        private String getBody() throws Exception {
            done.await(5, TimeUnit.SECONDS);
            if (error != null) {
                throw new IOException(error);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
