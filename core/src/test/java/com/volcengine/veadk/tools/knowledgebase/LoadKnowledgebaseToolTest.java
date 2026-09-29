package com.volcengine.veadk.tools.knowledgebase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.models.LlmRequest;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.volcengine.veadk.knowledgebase.BaseKnowledgebaseService;
import com.volcengine.veadk.knowledgebase.KnowledgebaseEntry;
import com.volcengine.veadk.knowledgebase.SearchKnowledgebaseResponse;
import io.reactivex.rxjava3.core.Single;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class LoadKnowledgebaseToolTest {

    @Test
    void loadKnowledgebase_returnsEntriesFromService() {
        BaseKnowledgebaseService svc = Mockito.mock(BaseKnowledgebaseService.class);
        LoadKnowledgebaseTool tool = new LoadKnowledgebaseTool(svc);

        List<KnowledgebaseEntry> entries =
                Collections.singletonList(
                        new KnowledgebaseEntry("content", Collections.emptyMap()));
        SearchKnowledgebaseResponse resp = new SearchKnowledgebaseResponse();
        resp.setKnowledgebaseEntries(entries);
        when(svc.searchKnowledgebase(eq("cats"))).thenReturn(Single.just(resp));

        ToolContext ctx = Mockito.mock(ToolContext.class);
        List<?> result = load(tool, "cats", ctx);

        assertEquals(1, result.size());
        assertEquals("content", ((Map<?, ?>) result.get(0)).get("content"));
    }

    @Test
    void loadKnowledgebase_keepsServicePerToolInstance() {
        LoadKnowledgebaseTool firstTool = new LoadKnowledgebaseTool(serviceReturning("first"));
        LoadKnowledgebaseTool secondTool = new LoadKnowledgebaseTool(serviceReturning("second"));
        ToolContext ctx = Mockito.mock(ToolContext.class);

        String first =
                (String) ((Map<?, ?>) load(firstTool, "same query", ctx).get(0)).get("content");
        String second =
                (String) ((Map<?, ?>) load(secondTool, "same query", ctx).get(0)).get("content");

        assertEquals("first", first);
        assertEquals("second", second);
    }

    @Test
    void processLlmRequest_appendsInstruction() {
        BaseKnowledgebaseService svc = Mockito.mock(BaseKnowledgebaseService.class);
        LoadKnowledgebaseTool tool = new LoadKnowledgebaseTool(svc);

        // Use a real Builder to avoid NullPointer inside super.processLlmRequest
        LlmRequest.Builder realBuilder =
                LlmRequest.builder().model("test-model").contents(Collections.emptyList());
        LlmRequest.Builder builderSpy = Mockito.spy(realBuilder);

        ToolContext ctx = Mockito.mock(ToolContext.class);
        tool.processLlmRequest(builderSpy, ctx).blockingAwait();

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(builderSpy).appendInstructions(captor.capture());
        @SuppressWarnings("unchecked")
        List<String> instructions = captor.getValue();
        assertEquals(1, instructions.size());
        assertTrue(instructions.get(0).contains("knowledgebase"));
        assertTrue(instructions.get(0).contains("loadKnowledgebase"));
    }

    private static List<?> load(BaseTool tool, String query, ToolContext ctx) {
        Map<String, Object> result =
                tool.runAsync(Collections.singletonMap("query", query), ctx).blockingGet();
        return (List<?>) result.get("knowledges");
    }

    private static BaseKnowledgebaseService serviceReturning(String content) {
        return query -> {
            SearchKnowledgebaseResponse response = new SearchKnowledgebaseResponse();
            response.setKnowledgebaseEntries(
                    Collections.singletonList(
                            new KnowledgebaseEntry(content, Collections.emptyMap())));
            return Single.just(response);
        };
    }
}
