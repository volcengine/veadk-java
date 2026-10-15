/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.volcengine.veadk.example.agentkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.memory.ShortTermMemory;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

public class AgentKitParityTest {
    private final HttpClient client = HttpClient.newHttpClient();

    static Event event(String text, boolean partial) {
        return Event.builder()
                .author("test_agent")
                .content(Content.builder().role("model").parts(Part.fromText(text)).build())
                .partial(partial)
                .build();
    }

    static class TestAgent extends BaseAgent {
        final Function<InvocationContext, Flowable<Event>> action;

        TestAgent(Function<InvocationContext, Flowable<Event>> action) {
            super("test_agent", "test", List.of(), List.of(), List.of());
            this.action = action;
        }

        protected Flowable<Event> runAsyncImpl(InvocationContext context) {
            return action.apply(context);
        }

        protected Flowable<Event> runLiveImpl(InvocationContext context) {
            return Flowable.empty();
        }
    }

    public static class Existing {
        public static final BaseAgent ROOT_AGENT =
                new TestAgent(c -> Flowable.just(event("loaded", false)));
    }

    public static class Wrong {
        public static final String ROOT_AGENT = "wrong";
    }

    public static class NonStatic {
        public final BaseAgent ROOT_AGENT = Existing.ROOT_AGENT;
    }

    public static class NullRoot {
        public static final BaseAgent ROOT_AGENT = null;
    }

    public static class ClosingRoot {
        static final AtomicInteger closes = new AtomicInteger();
        public static final BaseAgent ROOT_AGENT =
                new TestAgent(c -> Flowable.empty()) {
                    @Override
                    public Completable close() {
                        return Completable.fromAction(closes::incrementAndGet);
                    }
                };
    }

    private AgentKitRuntimeExample app(BaseAgent agent, boolean streaming) throws Exception {
        var app =
                new AgentKitRuntimeExample(new InetSocketAddress("127.0.0.1", 0), agent, streaming);
        app.start();
        return app;
    }

    private HttpRequest request(AgentKitRuntimeExample app, String user, String session) {
        var b =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/invoke"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json");
        if (user != null) b.header("user_id", user);
        if (session != null) b.header("session_id", session);
        return b.POST(HttpRequest.BodyPublishers.ofString("{\"prompt\":\"hello\"}")).build();
    }

    private HttpResponse<String> send(AgentKitRuntimeExample app, String user, String session)
            throws Exception {
        return client.send(request(app, user, session), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void persistsHistoryOnlyWithinTheSameUserAndSession() throws Exception {
        try (var app =
                app(
                        new TestAgent(
                                c ->
                                        Flowable.just(
                                                event(
                                                        "events=" + c.session().events().size(),
                                                        false))),
                        false)) {
            assertEquals("\"events=1\"", send(app, "alice", "same").body());
            assertEquals("\"events=3\"", send(app, "alice", "same").body());
            assertEquals("\"events=1\"", send(app, "bob", "same").body());
            assertEquals("\"events=1\"", send(app, "alice", "other").body());
            assertEquals("\"events=1\"", send(app, null, null).body());
            assertEquals("\"events=1\"", send(app, null, null).body());
        }
    }

    @Test
    void rejectsInvalidAndRepeatedContextHeaders() throws Exception {
        try (var app = app(Existing.ROOT_AGENT, false)) {
            assertEquals(400, send(app, "bad user", "session").statusCode());
            assertEquals(400, send(app, "alice", "!").statusCode());
            var r =
                    HttpRequest.newBuilder(request(app, "alice", "same"), (k, v) -> true)
                            .header("user_id", "bob")
                            .build();
            assertEquals(400, client.send(r, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    void overlappingSessionIsRejectedAndReleasedAfterCompletion() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var app =
                app(
                        new TestAgent(
                                c ->
                                        Flowable.fromCallable(
                                                        () -> {
                                                            entered.countDown();
                                                            assertTrue(
                                                                    release.await(
                                                                            5, TimeUnit.SECONDS));
                                                            return event("done", false);
                                                        })
                                                .subscribeOn(Schedulers.io())),
                        false)) {
            var first =
                    client.sendAsync(
                            request(app, "alice", "same"), HttpResponse.BodyHandlers.ofString());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(409, send(app, "alice", "same").statusCode());
            release.countDown();
            assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
            assertEquals(200, send(app, "alice", "same").statusCode());
        } finally {
            release.countDown();
        }
    }

    @Test
    void streamsActualEventBeforeFinalAndRedactsLaterFailure() throws Exception {
        var release = new CountDownLatch(1);
        var disposed = new AtomicBoolean();
        try (var app =
                app(
                        new TestAgent(
                                c ->
                                        Flowable.concat(
                                                        Flowable.just(event("incremental", true)),
                                                        Flowable.<Event>fromCallable(
                                                                        () -> {
                                                                            assertTrue(
                                                                                    release.await(
                                                                                            5,
                                                                                            TimeUnit
                                                                                                    .SECONDS));
                                                                            throw new IllegalStateException(
                                                                                    "private-secret");
                                                                        })
                                                                .subscribeOn(Schedulers.io()))
                                                .doFinally(() -> disposed.set(true))),
                        true)) {
            var response =
                    client.send(
                            request(app, "alice", "stream"),
                            HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            assertTrue(
                    response.headers()
                            .firstValue("Content-Type")
                            .orElseThrow()
                            .startsWith("text/event-stream"));
            try (var reader = new BufferedReader(new InputStreamReader(response.body()))) {
                String line;
                do {
                    line = reader.readLine();
                    assertNotNull(line);
                } while (!line.startsWith("data: "));
                assertTrue(line.contains("incremental"));
                assertTrue(line.contains("\"partial\":true"));
                assertEquals(1, release.getCount());
                release.countDown();
                String rest = reader.lines().reduce("", (a, b) -> a + b);
                assertTrue(rest.contains("event: error"));
                assertFalse(rest.contains("private-secret"));
            }
            assertTrue(disposed.get());
            assertEquals(200, send(app, "alice", "stream").statusCode());
        } finally {
            release.countDown();
        }
    }

    @Test
    void configuresAllSelectedToolsAndMetadata() throws Exception {
        var process = AgentKitTestJvm.process(AgentKitParityTest.class);
        process.environment().put("VOLCENGINE_ACCESS_KEY", "test-access-key");
        process.environment().put("VOLCENGINE_SECRET_KEY", "test-secret-key");
        var child = process.redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS));
            assertEquals(
                    0,
                    child.exitValue(),
                    new String(
                            child.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            child.destroy();
            if (!child.waitFor(5, TimeUnit.SECONDS))
                child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static void main(String[] args) {
        try (var config =
                AgentKitAgentConfiguration.fromEnvironment(
                        Map.of(
                                "MODEL_AGENT_NAME",
                                "test-model",
                                "MODEL_AGENT_API_KEY",
                                "test-key",
                                "AGENT_NAME",
                                "configured",
                                "AGENT_DESCRIPTION",
                                "description",
                                "MODEL_AGENT_INSTRUCTION",
                                "custom instruction",
                                "AGENTKIT_TOOLS",
                                "web_search,web_fetch,run_code,web_search"))) {
            var agent = (com.google.adk.agents.LlmAgent) config.agent();
            assertEquals("configured", agent.name());
            assertEquals("description", agent.description());
            assertEquals(
                    "custom instruction",
                    ((com.google.adk.agents.Instruction.Static) agent.instruction()).instruction());
            assertEquals(
                    List.of("web_search", "web_fetch", "run_code"),
                    agent.tools().blockingGet().stream().map(t -> t.name()).toList());
        }
    }

    @Test
    void disconnectCancelsUpstreamAndReleasesSession() throws Exception {
        var cancelled = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        try (var app =
                app(
                        new TestAgent(
                                c ->
                                        first.getAndSet(false)
                                                ? Flowable.interval(20, TimeUnit.MILLISECONDS)
                                                        .onBackpressureLatest()
                                                        .map(n -> event("x".repeat(262144), true))
                                                        .doOnCancel(cancelled::countDown)
                                                : Flowable.just(event("recovered", false))),
                        true)) {
            try (var socket = new java.net.Socket("127.0.0.1", app.port())) {
                socket.setSoTimeout(5000);
                socket.setSoLinger(true, 0);
                String body = "{\"prompt\":\"hello\"}";
                socket.getOutputStream()
                        .write(
                                ("POST /invoke HTTP/1.1\r\n"
                                                + "Host: localhost\r\n"
                                                + "user_id: alice\r\n"
                                                + "session_id: disconnect\r\n"
                                                + "Content-Length: "
                                                + body.length()
                                                + "\r\n\r\n"
                                                + body)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String line;
                do {
                    line = reader.readLine();
                    assertNotNull(line);
                } while (!line.startsWith("data: "));
            }
            assertTrue(
                    cancelled.await(5, TimeUnit.SECONDS),
                    "Disconnected stream must cancel the model subscription");
            HttpResponse<String> recovered = null;
            for (int i = 0; i < 20; i++) {
                recovered = send(app, "alice", "disconnect");
                if (recovered.statusCode() != 409) break;
                Thread.sleep(10);
            }
            assertEquals(200, recovered.statusCode());
            assertTrue(recovered.body().contains("recovered"));
        }
    }

    @Test
    void basicModelFailureIsRedactedAndSessionCanRetry() throws Exception {
        var fail = new AtomicBoolean(true);
        try (var app =
                app(
                        new TestAgent(
                                c ->
                                        fail.getAndSet(false)
                                                ? Flowable.error(
                                                        new IllegalStateException(
                                                                "private-model-credential"))
                                                : Flowable.just(event("recovered", false))),
                        false)) {
            var failed = send(app, "alice", "retry");
            assertEquals(500, failed.statusCode());
            assertTrue(failed.body().contains("InvocationError"));
            assertFalse(failed.body().contains("private-model-credential"));
            var retried = send(app, "alice", "retry");
            assertEquals(200, retried.statusCode());
            assertEquals("\"recovered\"", retried.body());
        }
    }

    @Test
    void sessionInitializationFailureIsRedactedAndDoesNotReserveTheSession() throws Exception {
        var calls = new AtomicInteger();
        var memory =
                ShortTermMemory.builder()
                        .local()
                        .afterCreateSessionCallback(
                                session -> {
                                    throw new IllegalStateException("private-session-backend");
                                })
                        .build();
        var model =
                new BaseLlm("test-model") {
                    @Override
                    public Flowable<LlmResponse> generateContent(
                            LlmRequest request, boolean streaming) {
                        calls.incrementAndGet();
                        return Flowable.just(
                                LlmResponse.builder()
                                        .content(
                                                Content.builder()
                                                        .role("model")
                                                        .parts(Part.fromText("recovered"))
                                                        .build())
                                        .build());
                    }

                    @Override
                    public BaseLlmConnection connect(LlmRequest request) {
                        throw new UnsupportedOperationException();
                    }
                };
        var agent = Agent.builder().name("test_agent").model(model).shortTermMemory(memory).build();
        try (var app = app(agent, false)) {
            var failed = send(app, "alice", "init_failure");
            assertEquals(500, failed.statusCode());
            assertTrue(failed.body().contains("Could not initialize session"));
            assertFalse(failed.body().contains("private-session-backend"));
            assertEquals(0, calls.get());
            // The backend created the session before its hook failed. Retrying must
            // reuse that session and be allowed to call the model.
            var retry = send(app, "alice", "init_failure");
            assertEquals(200, retry.statusCode());
            assertEquals("\"recovered\"", retry.body());
            assertEquals(1, calls.get());
        } finally {
            agent.close().blockingAwait();
        }
    }

    @Test
    void builtInDefaultsAndInvalidAgentConfiguration() {
        try (var config =
                AgentKitAgentConfiguration.fromEnvironment(
                        Map.of(
                                "MODEL_AGENT_NAME",
                                "test-model",
                                "MODEL_AGENT_API_KEY",
                                "test-key",
                                "AGENTKIT_AGENT_CLASS",
                                " ",
                                "AGENTKIT_TOOLS",
                                ", ,"))) {
            var agent = (com.google.adk.agents.LlmAgent) config.agent();
            assertEquals("agentkit_java", agent.name());
            assertEquals("An AgentKit Java agent.", agent.description());
            assertEquals(
                    "Answer the user's question concisely.",
                    ((com.google.adk.agents.Instruction.Static) agent.instruction()).instruction());
            assertTrue(agent.tools().blockingGet().isEmpty());
            assertEquals("test-model", agent.resolvedModel().model().orElseThrow().model());
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AgentKitAgentConfiguration.fromEnvironment(
                                Map.of(
                                        "MODEL_AGENT_NAME",
                                        "test-model",
                                        "MODEL_AGENT_API_KEY",
                                        "test-key",
                                        "AGENT_NAME",
                                        "invalid/name")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentKitAgentConfiguration.fromEnvironment(Map.of("MODEL_AGENT_NAME", " ")));
    }

    @Test
    void closesImportedAgentOnlyOnce() {
        var config =
                AgentKitAgentConfiguration.fromEnvironment(
                        Map.of("AGENTKIT_AGENT_CLASS", ClosingRoot.class.getName()));
        int before = ClosingRoot.closes.get();
        config.close();
        config.close();
        assertEquals(before + 1, ClosingRoot.closes.get());
    }

    @Test
    void loadsExistingRootAndRejectsInvalidContracts() {
        try (var config =
                AgentKitAgentConfiguration.fromEnvironment(
                        Map.of("AGENTKIT_AGENT_CLASS", Existing.class.getName()))) {
            assertSame(Existing.ROOT_AGENT, config.agent());
        }
        for (String name :
                List.of(
                        "bad/class",
                        "missing.Agent",
                        Wrong.class.getName(),
                        NonStatic.class.getName(),
                        NullRoot.class.getName())) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            AgentKitAgentConfiguration.fromEnvironment(
                                    Map.of("AGENTKIT_AGENT_CLASS", name)));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AgentKitAgentConfiguration.fromEnvironment(
                                Map.of("AGENTKIT_TOOLS", "unknown")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentKitAgentConfiguration.fromEnvironment(Map.of()));
    }
}
