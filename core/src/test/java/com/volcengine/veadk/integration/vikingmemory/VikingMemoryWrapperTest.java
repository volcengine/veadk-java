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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VikingMemoryWrapperTest {

    @Test
    void constructor_setsOptionalSessionToken() {
        VikingMemoryWrapper wrapper = new VikingMemoryWrapper("ak", "sk", "session-token");
        assertEquals("session-token", wrapper.getSessionToken());
    }

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
    void addSession_errorThrows() throws Exception {
        RawResponse mockResponse =
                new RawResponse(
                        null, SdkError.EHTTP.getNumber(), new Exception("add session failed"));
        when(vikingMemoryWrapper.json(anyString(), isNull(), anyString())).thenReturn(mockResponse);

        List<Message> messages = Collections.singletonList(new Message("user", "hello"));
        Metadata metadata = new Metadata("u1", "a1", System.currentTimeMillis());

        assertThrows(
                RuntimeException.class,
                () -> vikingMemoryWrapper.addSession("test-collection", messages, metadata));
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
    void searchMemory_errorThrows() throws Exception {
        RawResponse mockResponse =
                new RawResponse(null, SdkError.EHTTP.getNumber(), new Exception("search failed"));
        when(vikingMemoryWrapper.json(anyString(), isNull(), anyString())).thenReturn(mockResponse);

        assertThrows(
                RuntimeException.class,
                () ->
                        vikingMemoryWrapper.searchMemory(
                                "test-collection",
                                "user-1",
                                "query",
                                2,
                                Collections.singletonList("sys_event_v1")));
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
}
