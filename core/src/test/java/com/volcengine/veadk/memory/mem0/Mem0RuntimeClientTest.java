package com.volcengine.veadk.memory.mem0;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class Mem0RuntimeClientTest {

    @Test
    void addMemory_postsExpectedPayload() throws Exception {
        CapturingTransport transport = new CapturingTransport(200, "{\"ok\":true}");
        Mem0RuntimeClient client =
                new Mem0RuntimeClient("https://mem0.example.com/v1", "key", transport);

        boolean success = client.addMemory("user-1", List.of(new Mem0Message("user", "hello")));

        assertThat(success).isTrue();
        assertThat(transport.request.uri().toString())
                .isEqualTo("https://mem0.example.com/v1/memories/");
        assertThat(transport.request.headers().firstValue("Authorization")).contains("Token key");
        assertThat(readBody(transport.request))
                .contains("\"user_id\":\"user-1\"")
                .contains("\"async_mode\":true")
                .contains("\"output_format\":\"v1.1\"")
                .contains("\"content\":\"hello\"");
    }

    @Test
    void addMemory_supportsSynchronousWrite() throws Exception {
        CapturingTransport transport = new CapturingTransport(200, "{\"ok\":true}");
        Mem0RuntimeClient client =
                new Mem0RuntimeClient("https://mem0.example.com", "key", transport);

        boolean success =
                client.addMemory("user-1", List.of(new Mem0Message("user", "hello")), false);

        assertThat(success).isTrue();
        assertThat(readBody(transport.request)).contains("\"async_mode\":false");
    }

    @Test
    void searchMemory_parsesResultsObject() throws Exception {
        CapturingTransport transport =
                new CapturingTransport(
                        200,
                        "{\"results\":[{\"memory\":\"likes tea\",\"score\":0.9},"
                                + "{\"text\":\"prefers morning\"}]}");
        Mem0RuntimeClient client =
                new Mem0RuntimeClient("https://mem0.example.com", "key", transport);

        List<Mem0MemoryResult> results = client.searchMemory("user-1", "drink", 3);

        assertThat(transport.request.uri().toString())
                .isEqualTo("https://mem0.example.com/v2/memories/search/");
        assertThat(readBody(transport.request))
                .contains("\"query\":\"drink\"")
                .contains("\"top_k\":3");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).getMemory()).isEqualTo("likes tea");
        assertThat(results.get(0).getScore()).isEqualTo(0.9);
        assertThat(results.get(1).getMemory()).isEqualTo("prefers morning");
    }

    @Test
    void searchMemory_parsesRawArray() throws Exception {
        List<Mem0MemoryResult> results =
                Mem0RuntimeClient.parseSearchResults("[{\"summary\":\"one\"},\"two\"]");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getMemory()).isEqualTo("one");
        assertThat(results.get(1).getMemory()).isEqualTo("two");
    }

    @Test
    void normalizeBaseUrl_stripsVersionSuffix() {
        assertThat(Mem0RuntimeClient.normalizeBaseUrl(" https://api.mem0.ai/v1/ "))
                .isEqualTo("https://api.mem0.ai");
        assertThat(Mem0RuntimeClient.normalizeBaseUrl("https://api.mem0.ai/v2"))
                .isEqualTo("https://api.mem0.ai");
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

    private static class CapturingTransport implements Mem0RuntimeClient.RuntimeHttpTransport {
        private final int statusCode;
        private final String body;
        private HttpRequest request;

        private CapturingTransport(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public Mem0RuntimeClient.RuntimeHttpResponse send(HttpRequest request) {
            this.request = request;
            return new Mem0RuntimeClient.RuntimeHttpResponse(statusCode, body);
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
