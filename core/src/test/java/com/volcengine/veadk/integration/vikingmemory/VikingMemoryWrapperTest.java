package com.volcengine.veadk.integration.vikingmemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.Arrays;
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

class VikingMemoryWrapperTest {

    private VikingMemoryWrapper vikingMemoryWrapper;

    @BeforeEach
    void setUp() {
        vikingMemoryWrapper = Mockito.spy(new VikingMemoryWrapper("ak", "sk"));
    }

    @Test
    void isCollectionExists_true() throws Exception {
        byte[] data =
                JSONUtil.toJson(
                                Collections.singletonMap(
                                        "Result", Collections.singletonMap("Name", "some-name")))
                        .getBytes();
        RawResponse mockResponse = new RawResponse(data, SdkError.SUCCESS.getNumber(), null);
        when(vikingMemoryWrapper.json(anyString(), isNull(), anyString())).thenReturn(mockResponse);

        assertTrue(vikingMemoryWrapper.isCollectionExists("test-collection"));
    }

    @Test
    void isCollectionExists_false_on_error() throws Exception {
        RawResponse mockResponse =
                new RawResponse(
                        null, SdkError.EHTTP.getNumber(), new Exception("collection not exist"));
        when(vikingMemoryWrapper.json(anyString(), isNull(), anyString())).thenReturn(mockResponse);

        assertFalse(vikingMemoryWrapper.isCollectionExists("test-collection"));
    }

    @Test
    void isCollectionExists_false_on_missing_name() throws Exception {
        byte[] data =
                JSONUtil.toJson(Collections.singletonMap("Result", new HashMap<>())).getBytes();
        RawResponse mockResponse = new RawResponse(data, SdkError.SUCCESS.getNumber(), null);
        when(vikingMemoryWrapper.json(anyString(), isNull(), anyString())).thenReturn(mockResponse);

        assertFalse(vikingMemoryWrapper.isCollectionExists("test-collection"));
    }

    @Test
    void createCollection_true() throws Exception {
        byte[] data =
                JSONUtil.toJson(
                                Collections.singletonMap(
                                        "Result",
                                        Collections.singletonMap("ResourceId", "some-id")))
                        .getBytes();
        RawResponse mockResponse = new RawResponse(data, SdkError.SUCCESS.getNumber(), null);
        when(vikingMemoryWrapper.json(anyString(), isNull(), anyString())).thenReturn(mockResponse);

        assertTrue(
                vikingMemoryWrapper.createCollection(
                        "test-collection", Arrays.asList("sys_event_v1")));
    }

    @Test
    void createCollection_false() throws Exception {
        RawResponse mockResponse =
                new RawResponse(
                        null,
                        SdkError.EHTTP.getNumber(),
                        new Exception("collection already exist"));
        when(vikingMemoryWrapper.json(anyString(), isNull(), anyString())).thenReturn(mockResponse);

        assertFalse(
                vikingMemoryWrapper.createCollection(
                        "test-collection", Arrays.asList("sys_event_v1")));
    }

    @Test
    void addSession_true() throws Exception {
        byte[] data =
                JSONUtil.toJson(
                                Collections.singletonMap(
                                        "data",
                                        Collections.singletonMap("session_id", "some-session-id")))
                        .getBytes();
        RawResponse mockResponse = new RawResponse(data, SdkError.SUCCESS.getNumber(), null);
        when(vikingMemoryWrapper.json(anyString(), isNull(), anyString())).thenReturn(mockResponse);

        List<Message> messages =
                Arrays.asList(new Message("user", "hello"), new Message("assistant", "world"));
        Metadata metadata = new Metadata("u1", "a1", System.currentTimeMillis());

        assertTrue(vikingMemoryWrapper.addSession("test-collection", messages, metadata));
    }

    @Test
    void addSession_false() throws Exception {
        RawResponse mockResponse =
                new RawResponse(
                        null, SdkError.EHTTP.getNumber(), new Exception("add session failed"));
        when(vikingMemoryWrapper.json(anyString(), isNull(), anyString())).thenReturn(mockResponse);

        List<Message> messages = Collections.singletonList(new Message("user", "hello"));
        Metadata metadata = new Metadata("u1", "a1", System.currentTimeMillis());

        assertFalse(vikingMemoryWrapper.addSession("test-collection", messages, metadata));
    }

    @Test
    void searchMemory_success() throws Exception {
        String responseData =
                "{\"data\":{\"result_list\":[{\"memory_info\":{\"summary\":\"first\"}},"
                        + "{\"memory_info\":{\"summary\":\"second\"}}]}}";
        RawResponse mockResponse =
                new RawResponse(responseData.getBytes(), SdkError.SUCCESS.getNumber(), null);
        when(vikingMemoryWrapper.json(anyString(), isNull(), anyString())).thenReturn(mockResponse);

        List<String> eventTypes = Arrays.asList("sys_event_v1", "user_event_v1");
        List<com.google.adk.memory.MemoryEntry> result =
                vikingMemoryWrapper.searchMemory(
                        "test-collection", "user-1", "query", 2, eventTypes);
        assertEquals(2, result.size());
    }

    @Test
    void searchMemory_empty_on_error() throws Exception {
        RawResponse mockResponse =
                new RawResponse(null, SdkError.EHTTP.getNumber(), new Exception("search failed"));
        when(vikingMemoryWrapper.json(anyString(), isNull(), anyString())).thenReturn(mockResponse);

        List<com.google.adk.memory.MemoryEntry> result =
                vikingMemoryWrapper.searchMemory(
                        "test-collection",
                        "user-1",
                        "query",
                        2,
                        Collections.singletonList("sys_event_v1"));
        assertTrue(result.isEmpty());
    }

    @Test
    void searchMemory_empty_when_missing_result_list() throws Exception {
        byte[] data = JSONUtil.toJson(Collections.singletonMap("data", new HashMap<>())).getBytes();
        RawResponse mockResponse = new RawResponse(data, SdkError.SUCCESS.getNumber(), null);
        when(vikingMemoryWrapper.json(anyString(), isNull(), anyString())).thenReturn(mockResponse);

        List<com.google.adk.memory.MemoryEntry> result =
                vikingMemoryWrapper.searchMemory(
                        "test-collection",
                        "user-1",
                        "query",
                        2,
                        Collections.singletonList("sys_event_v1"));
        assertTrue(result.isEmpty());
    }

    @Test
    void searchMemory_withApiKey_postsBearerRequest() throws Exception {
        CapturingTransport transport =
                new CapturingTransport(
                        200,
                        "{\"data\":{\"result_list\":[{\"memory_info\":{\"summary\":\"first\"}}]}}");
        VikingMemoryWrapper wrapper = new VikingMemoryWrapper(null, null, "api-key", transport);

        List<com.google.adk.memory.MemoryEntry> result =
                wrapper.searchMemory(
                        "test_collection",
                        "user-1",
                        "query",
                        2,
                        Collections.singletonList("sys_event_v1"));

        assertEquals(1, result.size());
        assertEquals(
                "https://api-knowledgebase.mlp.cn-beijing.volces.com/api/memory/search",
                transport.request.uri().toString());
        assertEquals(
                Optional.of("Bearer api-key"),
                transport.request.headers().firstValue("Authorization"));
        assertTrue(readBody(transport.request).contains("\"collection_name\":\"test_collection\""));
        assertTrue(readBody(transport.request).contains("\"user_id\":\"user-1\""));
        assertTrue(readBody(transport.request).contains("\"limit\":2"));
    }

    @Test
    void addSession_withApiKey_postsBearerRequest() throws Exception {
        CapturingTransport transport =
                new CapturingTransport(200, "{\"data\":{\"session_id\":\"session-id\"}}");
        VikingMemoryWrapper wrapper = new VikingMemoryWrapper(null, null, "api-key", transport);
        List<Message> messages = Collections.singletonList(new Message("user", "hello"));
        Metadata metadata = new Metadata("u1", "a1", 1L);

        assertTrue(wrapper.addSession("test_collection", messages, metadata));

        assertEquals(
                "https://api-knowledgebase.mlp.cn-beijing.volces.com/api/memory/session/add",
                transport.request.uri().toString());
        assertEquals(
                Optional.of("Bearer api-key"),
                transport.request.headers().firstValue("Authorization"));
        assertTrue(readBody(transport.request).contains("\"collection_name\":\"test_collection\""));
        assertTrue(readBody(transport.request).contains("\"content\":\"hello\""));
    }

    @Test
    void addSession_withApiKeyFailure_returnsFalse() throws Exception {
        CapturingTransport transport = new CapturingTransport(403, "{\"message\":\"forbidden\"}");
        VikingMemoryWrapper wrapper = new VikingMemoryWrapper(null, null, "api-key", transport);

        assertFalse(
                wrapper.addSession(
                        "test_collection",
                        Collections.singletonList(new Message("user", "hello")),
                        new Metadata("u1", "a1", 1L)));
    }

    @Test
    void searchMemory_withApiKeyFailure_returnsEmptyList() throws Exception {
        CapturingTransport transport =
                new CapturingTransport(401, "{\"message\":\"unauthorized\"}");
        VikingMemoryWrapper wrapper = new VikingMemoryWrapper(null, null, "api-key", transport);

        List<com.google.adk.memory.MemoryEntry> result =
                wrapper.searchMemory(
                        "test_collection",
                        "user-1",
                        "query",
                        2,
                        Collections.singletonList("sys_event_v1"));

        assertTrue(result.isEmpty());
    }

    @Test
    void searchMemory_withApiKeyInterrupted_throwsAndRestoresInterrupt() {
        VikingMemoryWrapper wrapper =
                new VikingMemoryWrapper(null, null, "api-key", new InterruptingTransport());

        try {
            assertThrows(
                    IOException.class,
                    () ->
                            wrapper.searchMemory(
                                    "test_collection",
                                    "user-1",
                                    "query",
                                    2,
                                    Collections.singletonList("sys_event_v1")));

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

    private static class CapturingTransport implements VikingMemoryWrapper.VikingHttpTransport {
        private final int statusCode;
        private final String body;
        private HttpRequest request;

        private CapturingTransport(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public VikingMemoryWrapper.VikingHttpResponse send(HttpRequest request) {
            this.request = request;
            return new VikingMemoryWrapper.VikingHttpResponse(statusCode, body);
        }
    }

    private static class InterruptingTransport implements VikingMemoryWrapper.VikingHttpTransport {

        @Override
        public VikingMemoryWrapper.VikingHttpResponse send(HttpRequest request)
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
