package com.volcengine.veadk.tools.builtin.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MobileUseToolTest {

    @Test
    void executesTasksAcrossPodsAndCancelsForCleanup() {
        FakeClient client = new FakeClient();
        MobileUseTool tool =
                new MobileUseTool(
                        config(Duration.ofSeconds(10)), client, millis -> {}, System::nanoTime);

        Map<String, Object> output =
                tool.runAsync(Map.of("user_prompts", List.of("install", "search")), null)
                        .blockingGet();

        assertEquals(
                List.of("task success: done install", "task success: done search"),
                output.get("results"));
        assertEquals(2, client.cancelled.get());
        assertEquals(2, client.pods.size());
    }

    @Test
    void returnsFailureAndTimeoutInInputOrder() {
        AtomicLong time = new AtomicLong();
        FakeClient client = new FakeClient();
        client.failurePrompt = "fail";
        client.pendingPrompt = "wait";
        MobileUseTool tool =
                new MobileUseTool(
                        config(Duration.ofMillis(10)), client, time::addAndGet, time::get);

        @SuppressWarnings("unchecked")
        List<String> results =
                (List<String>)
                        tool.runAsync(Map.of("user_prompts", List.of("fail", "wait")), null)
                                .blockingGet()
                                .get("results");

        assertEquals("task failed: rejected", results.get(0));
        assertEquals("task failed: timed out after 0 seconds", results.get(1));
        assertEquals(2, client.cancelled.get());
    }

    @Test
    void validatesConfigurationAndPromptInput() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MobileUseTool(
                                new MobileUseTool.Config(
                                        "",
                                        List.of(),
                                        "system",
                                        Duration.ofSeconds(1),
                                        1,
                                        1,
                                        Duration.ZERO),
                                new FakeClient(),
                                millis -> {},
                                System::nanoTime));
        MobileUseTool tool =
                new MobileUseTool(
                        config(Duration.ofSeconds(1)),
                        new FakeClient(),
                        millis -> {},
                        System::nanoTime);
        assertTrue(
                tool.runAsync(Map.of("user_prompts", List.of(" ")), null)
                        .blockingGet()
                        .containsKey("error"));
    }

    private static MobileUseTool.Config config(Duration timeout) {
        return new MobileUseTool.Config(
                "product",
                List.of("pod-a", "pod-b"),
                "Follow the task safely.",
                timeout,
                10,
                1,
                Duration.ofMillis(5));
    }

    private static final class FakeClient implements MobileUseTool.Client {
        private final Map<String, String> prompts = new ConcurrentHashMap<>();
        private final Map<String, String> pods = new ConcurrentHashMap<>();
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicInteger cancelled = new AtomicInteger();
        private String failurePrompt;
        private String pendingPrompt;

        @Override
        public String start(
                String productId,
                String podId,
                String systemPrompt,
                String userPrompt,
                int maxSteps,
                int stepIntervalSeconds,
                int timeoutSeconds) {
            String id = "run-" + sequence.incrementAndGet();
            prompts.put(id, userPrompt);
            pods.put(id, podId);
            return id;
        }

        @Override
        public MobileUseTool.Result result(String runId) {
            String prompt = prompts.get(runId);
            if (prompt.equals(failurePrompt)) return new MobileUseTool.Result(2, "rejected");
            if (prompt.equals(pendingPrompt)) return new MobileUseTool.Result(0, "pending");
            return new MobileUseTool.Result(1, "done " + prompt);
        }

        @Override
        public void cancel(String runId) {
            cancelled.incrementAndGet();
        }
    }
}
