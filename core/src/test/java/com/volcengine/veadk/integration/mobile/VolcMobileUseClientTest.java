package com.volcengine.veadk.integration.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.volcengine.error.SdkError;
import com.volcengine.model.response.RawResponse;
import com.volcengine.veadk.utils.JSONUtil;
import java.util.List;
import org.apache.http.NameValuePair;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VolcMobileUseClientTest {

    @Test
    void startsTaskWithExpectedApiContract() throws Exception {
        VolcMobileUseClient client = spy(new VolcMobileUseClient("ak", "sk", "token"));
        when(client.json(anyString(), anyList(), anyString()))
                .thenReturn(success("{\"Result\":{\"RunId\":\"run-1\"}}"));

        assertEquals("run-1", client.start("product", "pod", "system", "user", 42, 3, 90));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> params = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client).json(eq("RunAgentTaskOneStep"), params.capture(), body.capture());
        assertParameter(params.getValue(), "Action", "RunAgentTaskOneStep");
        assertParameter(params.getValue(), "Version", "2023-08-01");
        JsonNode payload = JSONUtil.parseJson(body.getValue());
        assertEquals("product", payload.path("ProductId").asText());
        assertEquals("pod", payload.path("PodId").asText());
        assertEquals("system", payload.path("SystemPrompt").asText());
        assertEquals("user", payload.path("UserPrompt").asText());
        assertEquals(42, payload.path("MaxStep").asInt());
        assertEquals(3, payload.path("StepInterval").asInt());
        assertEquals(90, payload.path("Timeout").asInt());
    }

    @Test
    void readsResultCancelsByRunIdAndRejectsInvalidResponses() throws Exception {
        VolcMobileUseClient client = spy(new VolcMobileUseClient("ak", "sk", ""));
        when(client.json(eq("GetAgentResult"), anyList(), anyString()))
                .thenReturn(success("{\"Result\":{\"IsSuccess\":1,\"Content\":\"done\"}}"));
        when(client.json(eq("CancelTask"), anyList(), anyString())).thenReturn(success("{}"));

        var result = client.result("run-2");
        assertEquals(1, result.status());
        assertEquals("done", result.content());
        client.cancel("run-2");

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> resultParams = ArgumentCaptor.forClass(List.class);
        verify(client).json(eq("GetAgentResult"), resultParams.capture(), eq("{}"));
        assertParameter(resultParams.getValue(), "RunId", "run-2");
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> cancelParams = ArgumentCaptor.forClass(List.class);
        verify(client).json(eq("CancelTask"), cancelParams.capture(), eq("{}"));
        assertParameter(cancelParams.getValue(), "RunId", "run-2");

        when(client.json(eq("GetAgentResult"), anyList(), anyString()))
                .thenReturn(
                        new RawResponse(null, SdkError.EHTTP.getNumber(), new Exception("bad")));
        assertThrows(IllegalStateException.class, () -> client.result("run-3"));
    }

    private static RawResponse success(String json) {
        return new RawResponse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, null);
    }

    @SuppressWarnings("rawtypes")
    private static void assertParameter(List parameters, String name, String value) {
        assertTrue(
                parameters.stream()
                        .anyMatch(
                                raw -> {
                                    NameValuePair item = (NameValuePair) raw;
                                    return item.getName().equals(name)
                                            && item.getValue().equals(value);
                                }));
    }
}
