package com.volcengine.veadk.integration.vikingknowledgebase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.volcengine.error.SdkError;
import com.volcengine.model.response.RawResponse;
import com.volcengine.veadk.utils.JSONUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VikingKnowledgebaseWrapperTest {

    private VikingKnowledgebaseWrapper vikingKnowledgebaseWrapper;

    @BeforeEach
    void setUp() {
        vikingKnowledgebaseWrapper = Mockito.spy(new VikingKnowledgebaseWrapper("ak", "sk"));
    }

    @Test
    void isCollectionExists_true() throws Exception {
        byte[] data =
                JSONUtil.toJson(
                                Collections.singletonMap(
                                        "data", Collections.singletonMap("resource_id", "some-id")))
                        .getBytes();
        RawResponse mockResponse = new RawResponse(data, SdkError.SUCCESS.getNumber(), null);
        when(vikingKnowledgebaseWrapper.json(anyString(), isNull(), anyString()))
                .thenReturn(mockResponse);

        assertTrue(vikingKnowledgebaseWrapper.isCollectionExists("test-collection"));
    }

    @Test
    void isCollectionExists_false() throws Exception {
        RawResponse mockResponse =
                new RawResponse(
                        null, SdkError.EHTTP.getNumber(), new Exception("collection not exist"));
        when(vikingKnowledgebaseWrapper.json(anyString(), isNull(), anyString()))
                .thenReturn(mockResponse);

        assertFalse(vikingKnowledgebaseWrapper.isCollectionExists("test-collection"));
    }

    @Test
    void createCollection_true() throws Exception {
        byte[] data =
                JSONUtil.toJson(
                                Collections.singletonMap(
                                        "data", Collections.singletonMap("resource_id", "some-id")))
                        .getBytes();
        RawResponse mockResponse = new RawResponse(data, SdkError.SUCCESS.getNumber(), null);
        when(vikingKnowledgebaseWrapper.json(anyString(), isNull(), anyString()))
                .thenReturn(mockResponse);

        assertTrue(vikingKnowledgebaseWrapper.createCollection("test-collection"));
    }

    @Test
    void createCollection_false() throws Exception {
        RawResponse mockResponse =
                new RawResponse(
                        null,
                        SdkError.EHTTP.getNumber(),
                        new Exception("collection already exist"));
        when(vikingKnowledgebaseWrapper.json(anyString(), isNull(), anyString()))
                .thenReturn(mockResponse);

        assertFalse(vikingKnowledgebaseWrapper.createCollection("test-collection"));
    }

    @Test
    void addDoc_true() throws Exception {
        byte[] data =
                JSONUtil.toJson(
                                Collections.singletonMap(
                                        "data", Collections.singletonMap("doc_id", "some-doc-id")))
                        .getBytes();
        RawResponse mockResponse = new RawResponse(data, SdkError.SUCCESS.getNumber(), null);
        when(vikingKnowledgebaseWrapper.json(anyString(), isNull(), anyString()))
                .thenReturn(mockResponse);

        assertTrue(vikingKnowledgebaseWrapper.addDoc("test-collection", "tos://some-url"));
    }

    @Test
    void addDoc_false() throws Exception {
        RawResponse mockResponse =
                new RawResponse(null, SdkError.EHTTP.getNumber(), new Exception("add doc failed"));
        when(vikingKnowledgebaseWrapper.json(anyString(), isNull(), anyString()))
                .thenReturn(mockResponse);

        assertFalse(vikingKnowledgebaseWrapper.addDoc("test-collection", "tos://some-url"));
    }

    @Test
    void searchKnowledge_success() throws Exception {
        String responseData =
                "{\"data\":{\"result_list\":[{\"content\":\"test"
                    + " content\",\"doc_info\":{\"doc_meta\":\"[{\\\"field_name\\\":\\\"key\\\",\\\"field_value\\\":\\\"value\\\"}]\"}}]}}";
        RawResponse mockResponse =
                new RawResponse(responseData.getBytes(), SdkError.SUCCESS.getNumber(), null);
        when(vikingKnowledgebaseWrapper.json(anyString(), isNull(), anyString()))
                .thenReturn(mockResponse);

        List<KnowledgebaseEntry> result =
                vikingKnowledgebaseWrapper.searchKnowledge(
                        "test-collection", "query", 1, new HashMap<>(), false, 0);
        assertEquals(1, result.size());
        assertEquals("test content", result.get(0).getContent());
        assertEquals("value", result.get(0).getMetadata().get("key"));
    }

    @Test
    void searchKnowledge_empty() throws Exception {
        RawResponse mockResponse =
                new RawResponse(null, SdkError.EHTTP.getNumber(), new Exception("search failed"));
        when(vikingKnowledgebaseWrapper.json(anyString(), isNull(), anyString()))
                .thenReturn(mockResponse);

        List<KnowledgebaseEntry> result =
                vikingKnowledgebaseWrapper.searchKnowledge(
                        "test-collection", "query", 1, new HashMap<>(), false, 0);
        assertTrue(result.isEmpty());
    }

    @Test
    void searchKnowledge_withApiKey_postsBearerRequest() throws Exception {
        CapturingTransport transport =
                new CapturingTransport(
                        200,
                        "{\"data\":{\"result_list\":[{\"content\":\"api key content\","
                            + "\"doc_info\":{\"doc_meta\":\"[{\\\"field_name\\\":\\\"source\\\","
                            + "\\\"field_value\\\":\\\"unit\\\"}]\"}}]}}");
        VikingKnowledgebaseWrapper wrapper =
                new VikingKnowledgebaseWrapper(null, null, "api-key", transport);

        List<KnowledgebaseEntry> result =
                wrapper.searchKnowledge(
                        "test_collection",
                        "query",
                        2,
                        Collections.singletonMap("kind", "doc"),
                        true,
                        3);

        assertEquals(1, result.size());
        assertEquals("api key content", result.get(0).getContent());
        assertEquals("unit", result.get(0).getMetadata().get("source"));
        assertEquals(
                "https://api-knowledgebase.mlp.cn-beijing.volces.com/api/knowledge/collection/search_knowledge",
                transport.request.uri().toString());
        assertEquals(
                Optional.of("Bearer api-key"),
                transport.request.headers().firstValue("Authorization"));
        assertTrue(readBody(transport.request).contains("\"name\":\"test_collection\""));
        assertTrue(readBody(transport.request).contains("\"query\":\"query\""));
    }

    @Test
    void searchKnowledge_withApiKeyFailure_returnsEmptyList() {
        CapturingTransport transport =
                new CapturingTransport(401, "{\"message\":\"unauthorized\"}");
        VikingKnowledgebaseWrapper wrapper =
                new VikingKnowledgebaseWrapper(null, null, "api-key", transport);

        List<KnowledgebaseEntry> result =
                wrapper.searchKnowledge("test_collection", "query", 2, null, true, 3);

        assertTrue(result.isEmpty());
    }

    @Test
    void searchKnowledge_withApiKeyInterrupted_returnsEmptyListAndRestoresInterrupt() {
        VikingKnowledgebaseWrapper wrapper =
                new VikingKnowledgebaseWrapper(null, null, "api-key", new InterruptingTransport());

        try {
            List<KnowledgebaseEntry> result =
                    wrapper.searchKnowledge("test_collection", "query", 2, null, true, 3);

            assertTrue(result.isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
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

    private static class CapturingTransport
            implements VikingKnowledgebaseWrapper.VikingHttpTransport {
        private final int statusCode;
        private final String body;
        private HttpRequest request;

        private CapturingTransport(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public VikingKnowledgebaseWrapper.VikingHttpResponse send(HttpRequest request) {
            this.request = request;
            return new VikingKnowledgebaseWrapper.VikingHttpResponse(statusCode, body);
        }
    }

    private static class InterruptingTransport
            implements VikingKnowledgebaseWrapper.VikingHttpTransport {

        @Override
        public VikingKnowledgebaseWrapper.VikingHttpResponse send(HttpRequest request)
                throws InterruptedException {
            throw new InterruptedException("interrupted");
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
