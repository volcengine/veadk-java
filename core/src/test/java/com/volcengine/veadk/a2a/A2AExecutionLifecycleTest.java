package com.volcengine.veadk.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.memory.ShortTermMemory;
import io.a2a.util.Utils;
import io.reactivex.rxjava3.core.Flowable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class A2AExecutionLifecycleTest {
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void restPreservesContextAndDoesNotLeakIntoAnotherContext() throws Exception {
        Agent agent = agent();
        try (var server =
                new VeA2AServer(agent, "http://localhost", "test_app", ShortTermMemory.local())
                        .start("127.0.0.1", 0)) {
            var first = send(server, "same-context", "ORDER-58472");
            assertThat(first.path("task").path("artifacts").toString()).contains("ORDER-58472");
            var second = send(server, "same-context", "recall");
            assertThat(second.path("task").path("artifacts").toString()).contains("ORDER-58472");
            var other = send(server, "other-context", "recall");
            assertThat(other.path("task").path("artifacts").toString())
                    .doesNotContain("ORDER-58472");
        } finally {
            agent.close().blockingAwait();
        }
    }

    @Test
    void restBlockingSendWaitsForSlowModelAndPersistsFinalTask() throws Exception {
        Agent agent = agent();
        try (var server =
                new VeA2AServer(agent, "http://localhost", "test_app", ShortTermMemory.local())
                        .start("127.0.0.1", 0)) {
            var result = send(server, "slow-context", "slow-response");
            assertThat(result.path("task").path("status").path("state").asText())
                    .isEqualTo("TASK_STATE_COMPLETED");
            assertThat(result.path("task").path("artifacts").toString()).contains("slow-response");
        } finally {
            agent.close().blockingAwait();
        }
    }

    @Test
    void timeoutCancelsModel() throws Exception {
        Model model = new Model();
        Agent agent = Agent.builder().name("test_agent").model(model).build();
        try (var server =
                new VeA2AServer(
                                agent,
                                "http://localhost",
                                "test_app",
                                ShortTermMemory.local(),
                                Duration.ofSeconds(1))
                        .start("127.0.0.1", 0)) {
            var timedOut = send(server, "timeout-context", "never-response");
            assertThat(timedOut.path("task").path("status").path("state").asText())
                    .isEqualTo("TASK_STATE_CANCELLED");
            assertThat(model.disposed.await(2, TimeUnit.SECONDS)).isTrue();

        } finally {
            agent.close().blockingAwait();
        }
    }

    @Test
    void failureDoesNotExposeException() throws Exception {
        Agent agent = agent();
        try (var server =
                new VeA2AServer(agent, "http://localhost", "test_app", ShortTermMemory.local())
                        .start("127.0.0.1", 0)) {
            var failed = send(server, "failed-context", "failed-response");
            assertThat(failed.path("task").path("status").path("state").asText())
                    .isEqualTo("TASK_STATE_FAILED");
            assertThat(failed.toString()).doesNotContain("internal-test-detail");
        } finally {
            agent.close().blockingAwait();
        }
    }

    @Test
    void interruptedProducerCancelsWorkAndRejectsDuplicateExecution() throws Exception {
        Model model = new Model();
        Agent agent = Agent.builder().name("test_agent").model(model).build();
        var executor =
                new VeA2AExecutor(
                        agent,
                        "test_app",
                        ShortTermMemory.local().sessionService(),
                        new com.google.adk.artifacts.InMemoryArtifactService(),
                        new com.google.adk.memory.InMemoryMemoryService(),
                        Duration.ofSeconds(10));
        var params =
                Utils.OBJECT_MAPPER.readValue(
                        "{\"message\":{\"kind\":\"message\",\"role\":\"user\",\"messageId\":\"m\",\"taskId\":\"t\",\"contextId\":\"c\",\"parts\":[{\"kind\":\"text\",\"text\":\"never-response\"}]}}",
                        io.a2a.spec.MessageSendParams.class);
        var context =
                new io.a2a.server.agentexecution.RequestContext.Builder().setParams(params).build();
        var queue = new io.a2a.server.events.EventQueue.EventQueueBuilder().build();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread producer =
                new Thread(
                        () -> {
                            try {
                                executor.execute(context, queue);
                            } catch (Throwable error) {
                                failure.set(error);
                                interrupted.set(Thread.currentThread().isInterrupted());
                            }
                        });
        try {
            producer.start();
            assertThat(model.entered.await(3, TimeUnit.SECONDS)).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> executor.execute(context, queue))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already executing");
            producer.interrupt();
            producer.join(3000);
            assertThat(producer.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
            assertThat(interrupted).isTrue();
            assertThat(model.disposed.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            producer.interrupt();
            producer.join(3000);
            agent.close().blockingAwait();
        }
    }

    @Test
    void rejectsInvalidExecutionBudgets() {
        Agent agent = agent();
        try {
            for (Duration invalid :
                    List.of(
                            Duration.ZERO,
                            Duration.ofSeconds(-1),
                            Duration.ofNanos(1),
                            Duration.ofSeconds(Long.MAX_VALUE))) {
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () ->
                                        new VeA2AExecutor(
                                                agent,
                                                "test_app",
                                                ShortTermMemory.local().sessionService(),
                                                new com.google.adk.artifacts
                                                        .InMemoryArtifactService(),
                                                new com.google.adk.memory.InMemoryMemoryService(),
                                                invalid))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        } finally {
            agent.close().blockingAwait();
        }
    }

    private com.fasterxml.jackson.databind.JsonNode send(
            VeA2AServer server, String context, String text) throws Exception {
        String payload =
                Utils.toJsonString(
                        Map.of(
                                "message",
                                Map.of(
                                        "role",
                                        "ROLE_USER",
                                        "messageId",
                                        UUID.randomUUID().toString(),
                                        "contextId",
                                        context,
                                        "content",
                                        List.of(Map.of("text", text)))));
        var response =
                client.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + server.port()
                                                        + "/v1/message:send"))
                                .timeout(Duration.ofSeconds(20))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(payload))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return Utils.OBJECT_MAPPER.readTree(response.body());
    }

    private Agent agent() {
        return Agent.builder().name("test_agent").model(new Model()).build();
    }

    private static final class Model extends BaseLlm {
        final java.util.concurrent.CountDownLatch entered =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch disposed =
                new java.util.concurrent.CountDownLatch(1);

        Model() {
            super("test-model");
        }

        @Override
        public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
            String answer = request.contents().toString();
            if (answer.contains("never-response"))
                return Flowable.<LlmResponse>never()
                        .doOnSubscribe(subscription -> entered.countDown())
                        .doOnCancel(disposed::countDown);
            if (answer.contains("failed-response"))
                return Flowable.error(new IllegalStateException("internal-test-detail"));
            var response =
                    Flowable.just(
                            LlmResponse.builder()
                                    .content(Content.fromParts(Part.fromText(answer)))
                                    .build());
            return answer.contains("slow-response")
                    ? response.delay(6, TimeUnit.SECONDS)
                    : response;
        }

        @Override
        public BaseLlmConnection connect(LlmRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
