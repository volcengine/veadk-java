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
import com.volcengine.veadk.integration.VikingApiKeyHttpClient;
import com.volcengine.veadk.utils.JSONUtil;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    void addSession_withApiKeyUsesDataPlaneClient() throws Exception {
        VikingApiKeyHttpClient apiKeyClient = Mockito.mock(VikingApiKeyHttpClient.class);
        VikingMemoryWrapper wrapper = new VikingMemoryWrapper(null, null, apiKeyClient);
        when(apiKeyClient.post(
                        Mockito.eq("add memory session"),
                        Mockito.eq(VikingApiKeyHttpClient.MEMORY_ADD_PATH),
                        Mockito.anyMap()))
                .thenReturn(
                        JSONUtil.parseJson("{\"code\":0,\"data\":{\"session_id\":\"session-1\"}}"));
        List<Message> messages = List.of(new Message("user", "hello"));
        Metadata metadata = new Metadata("user-1", "assistant", 1L);

        assertTrue(wrapper.addSession("MemoryApp", messages, metadata));

        org.mockito.ArgumentCaptor<Map> bodyCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        Mockito.verify(apiKeyClient)
                .post(
                        Mockito.eq("add memory session"),
                        Mockito.eq(VikingApiKeyHttpClient.MEMORY_ADD_PATH),
                        bodyCaptor.capture());
        assertEquals("MemoryApp", bodyCaptor.getValue().get("collection_name"));
        assertEquals(messages, bodyCaptor.getValue().get("messages"));
        assertEquals(metadata, bodyCaptor.getValue().get("metadata"));
    }

    @Test
    void addSession_withApiKeyRejectsMissingSessionId() throws Exception {
        VikingApiKeyHttpClient apiKeyClient = Mockito.mock(VikingApiKeyHttpClient.class);
        VikingMemoryWrapper wrapper = new VikingMemoryWrapper(null, null, apiKeyClient);
        when(apiKeyClient.post(Mockito.anyString(), Mockito.anyString(), Mockito.anyMap()))
                .thenReturn(JSONUtil.parseJson("{\"code\":0,\"data\":{}}"));

        assertThrows(
                IllegalStateException.class,
                () ->
                        wrapper.addSession(
                                "MemoryApp",
                                List.of(new Message("user", "hello")),
                                new Metadata("user-1", "assistant", 1L)));
    }

    @Test
    void searchMemory_withApiKeyUsesDataPlaneClientAndParsesResults() throws Exception {
        VikingApiKeyHttpClient apiKeyClient = Mockito.mock(VikingApiKeyHttpClient.class);
        VikingMemoryWrapper wrapper = new VikingMemoryWrapper(null, null, apiKeyClient);
        when(apiKeyClient.post(
                        Mockito.eq("search memory"),
                        Mockito.eq(VikingApiKeyHttpClient.MEMORY_SEARCH_PATH),
                        Mockito.anyMap()))
                .thenReturn(
                        JSONUtil.parseJson(
                                "{\"code\":0,\"data\":{\"result_list\":[{\"memory_info\":{\"summary\":\"first\"}}]}}"));

        List<com.google.adk.memory.MemoryEntry> entries =
                wrapper.searchMemory("MemoryApp", "user-1", "query", 4, List.of("sys_event_v1"));

        assertEquals(1, entries.size());
        org.mockito.ArgumentCaptor<Map> bodyCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        Mockito.verify(apiKeyClient)
                .post(
                        Mockito.eq("search memory"),
                        Mockito.eq(VikingApiKeyHttpClient.MEMORY_SEARCH_PATH),
                        bodyCaptor.capture());
        assertEquals(4, bodyCaptor.getValue().get("limit"));
        assertEquals("query", bodyCaptor.getValue().get("query"));
        assertTrue(bodyCaptor.getValue().get("filter") instanceof Map);
    }

    @Test
    void searchMemory_withApiKeyDistinguishesEmptyAndMalformedResults() throws Exception {
        VikingApiKeyHttpClient apiKeyClient = Mockito.mock(VikingApiKeyHttpClient.class);
        VikingMemoryWrapper wrapper = new VikingMemoryWrapper(null, null, apiKeyClient);
        when(apiKeyClient.post(Mockito.anyString(), Mockito.anyString(), Mockito.anyMap()))
                .thenReturn(
                        JSONUtil.parseJson("{\"code\":0,\"data\":{\"result_list\":[]}}"),
                        JSONUtil.parseJson("{\"code\":0,\"data\":{}}"));

        assertTrue(wrapper.searchMemory("MemoryApp", "user-1", "query", 1, List.of()).isEmpty());
        assertThrows(
                IllegalStateException.class,
                () -> wrapper.searchMemory("MemoryApp", "user-1", "query", 1, List.of()));
    }

    @Test
    void apiKeyOnlyManagementOperationRequiresAkSk() {
        VikingMemoryWrapper wrapper =
                new VikingMemoryWrapper(null, null, Mockito.mock(VikingApiKeyHttpClient.class));

        assertThrows(IllegalStateException.class, () -> wrapper.isCollectionExists("MemoryApp"));
        assertThrows(
                IllegalStateException.class,
                () -> wrapper.createCollection("MemoryApp", List.of("sys_event_v1")));
    }
}
