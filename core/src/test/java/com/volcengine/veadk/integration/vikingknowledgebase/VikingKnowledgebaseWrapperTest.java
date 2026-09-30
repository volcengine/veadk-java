package com.volcengine.veadk.integration.vikingknowledgebase;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VikingKnowledgebaseWrapperTest {

    @Test
    void constructor_setsOptionalSessionToken() {
        VikingKnowledgebaseWrapper wrapper =
                new VikingKnowledgebaseWrapper("ak", "sk", "session-token");
        assertEquals("session-token", wrapper.getSessionToken());
    }

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
    void searchKnowledge_errorThrows() throws Exception {
        RawResponse mockResponse =
                new RawResponse(null, SdkError.EHTTP.getNumber(), new Exception("search failed"));
        when(vikingKnowledgebaseWrapper.json(anyString(), isNull(), anyString()))
                .thenReturn(mockResponse);

        assertThrows(
                RuntimeException.class,
                () ->
                        vikingKnowledgebaseWrapper.searchKnowledge(
                                "test-collection", "query", 1, new HashMap<>(), false, 0));
    }
}
