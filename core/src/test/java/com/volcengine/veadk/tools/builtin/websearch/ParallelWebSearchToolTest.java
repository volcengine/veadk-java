package com.volcengine.veadk.tools.builtin.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class ParallelWebSearchToolTest {

    @Test
    void searchesDistinctQueriesAndPreservesInputOrder() {
        Map<String, String> threads = new ConcurrentHashMap<>();
        ParallelWebSearchTool tool =
                new ParallelWebSearchTool(
                        query -> {
                            threads.put(query, Thread.currentThread().getName());
                            return List.of(query + " result");
                        });

        Map<String, Object> result =
                tool.runAsync(Map.of("queries", List.of("alpha", "beta", "alpha", " ")), null)
                        .blockingGet();

        assertEquals(List.of("alpha", "beta"), result.keySet().stream().toList());
        assertEquals(List.of("alpha result"), result.get("alpha"));
        assertEquals(2, threads.size());
        assertTrue(threads.values().stream().noneMatch(Thread.currentThread().getName()::equals));
    }

    @Test
    void isolatesPerQueryFailuresAndRejectsEmptyInput() {
        ParallelWebSearchTool tool =
                new ParallelWebSearchTool(
                        query -> {
                            if (query.equals("bad")) throw new IllegalStateException("unavailable");
                            return List.of("ok");
                        });

        Map<String, Object> result =
                tool.runAsync(Map.of("queries", List.of("ok", "bad")), null).blockingGet();

        assertEquals(List.of("ok"), result.get("ok"));
        assertEquals(List.of("Web search failed: unavailable"), result.get("bad"));
        assertTrue(
                tool.runAsync(Map.of("queries", List.of()), null)
                        .blockingGet()
                        .containsKey("error"));
    }
}
