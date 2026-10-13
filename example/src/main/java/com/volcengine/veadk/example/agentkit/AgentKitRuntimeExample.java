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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.volcengine.veadk.Runner;
import io.reactivex.rxjava3.disposables.Disposable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** AgentKit Simple App HTTP runtime with user-scoped sessions and optional SSE. */
public final class AgentKitRuntimeExample implements AutoCloseable {
    static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ExecutorService invocations =
            new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
    private final Function<String, String> invoke;
    private final Runner runner;
    private final boolean streaming;
    private final ConcurrentHashMap<SessionKey, Boolean> activeSessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private record SessionKey(String userId, String sessionId) {}

    public AgentKitRuntimeExample(InetSocketAddress address, Function<String, String> invoke)
            throws IOException {
        this(address, Objects.requireNonNull(invoke, "invoke must be set"), null, false);
    }

    public AgentKitRuntimeExample(InetSocketAddress address, BaseAgent agent, boolean streaming)
            throws IOException {
        this(
                address,
                null,
                new Runner(Objects.requireNonNull(agent, "agent must be set")),
                streaming);
    }

    private AgentKitRuntimeExample(
            InetSocketAddress address,
            Function<String, String> invoke,
            Runner runner,
            boolean streaming)
            throws IOException {
        this.invoke = invoke;
        this.runner = runner;
        this.streaming = streaming;
        server = HttpServer.create(address, 16);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (exchange.getRequestURI().getPath().equals("/invoke")
                && exchange.getRequestMethod().equals("POST")) {
            try {
                // The worker owns the exchange, including reading its body and closing it.
                invocations.execute(
                        () -> {
                            try {
                                handleInvocation(exchange);
                            } catch (IOException failure) {
                                System.getLogger(AgentKitRuntimeExample.class.getName())
                                        .log(
                                                System.Logger.Level.WARNING,
                                                "Failed to complete HTTP invocation");
                            }
                        });
            } catch (RejectedExecutionException overloaded) {
                try (exchange) {
                    error(exchange, 503, "ServiceUnavailable", "All invocation workers are busy");
                }
            }
            return;
        }
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/ping")
                    || path.equals("/health")
                    || path.equals("/readiness")
                    || path.equals("/liveness")) {
                if (!requireMethod(exchange, "GET")) return;
                send(exchange, 200, Map.of("status", path.equals("/ping") ? "pong!" : "healthy"));
                return;
            }
            if (!path.equals("/invoke")) {
                error(exchange, 404, "NotFound", "Route not found");
                return;
            }
            if (!requireMethod(exchange, "POST")) return;
        }
    }

    private void handleInvocation(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) {
                error(exchange, 413, "PayloadTooLarge", "Request exceeds 1 MiB");
                return;
            }
            JsonNode payload;
            try {
                payload =
                        JSON.reader()
                                .with(
                                        com.fasterxml.jackson.databind.DeserializationFeature
                                                .FAIL_ON_TRAILING_TOKENS)
                                .readTree(bytes);
            } catch (IOException invalidJson) {
                error(
                        exchange,
                        400,
                        "BadRequest",
                        "Expected a JSON object with a non-empty prompt string");
                return;
            }
            if (payload == null
                    || !payload.isObject()
                    || !payload.path("prompt").isTextual()
                    || payload.path("prompt").asText().isBlank()) {
                error(
                        exchange,
                        400,
                        "BadRequest",
                        "Expected a JSON object with a non-empty prompt string");
                return;
            }
            String prompt = payload.get("prompt").asText();
            if (runner == null) {
                try {
                    send(exchange, 200, invoke.apply(prompt));
                } catch (RuntimeException failure) {
                    error(exchange, 500, "InvocationError", "Agent invocation failed");
                }
                return;
            }
            SessionKey key;
            try {
                key =
                        new SessionKey(
                                contextHeader(exchange, "user_id", "agentkit_user"),
                                contextHeader(
                                        exchange, "session_id", UUID.randomUUID().toString()));
            } catch (IllegalArgumentException invalid) {
                error(exchange, 400, "BadRequest", "Invalid user_id or session_id header");
                return;
            }
            if (activeSessions.putIfAbsent(key, Boolean.TRUE) != null) {
                error(exchange, 409, "SessionBusy", "Another invocation is using this session");
                return;
            }
            try {
                try {
                    runner.shortTermMemory()
                            .createSession(runner.appName(), key.userId(), key.sessionId())
                            .blockingGet();
                } catch (RuntimeException failure) {
                    error(exchange, 500, "InvocationError", "Could not initialize session");
                    return;
                }
                if (streaming) {
                    stream(exchange, key, prompt);
                } else {
                    try {
                        send(
                                exchange,
                                200,
                                runner.run(
                                        key.userId(),
                                        key.sessionId(),
                                        prompt,
                                        RunConfig.builder().autoCreateSession(false).build()));
                    } catch (RuntimeException failure) {
                        error(exchange, 500, "InvocationError", "Agent invocation failed");
                    }
                }
            } finally {
                activeSessions.remove(key);
            }
        }
    }

    private static String contextHeader(HttpExchange exchange, String name, String fallback) {
        var values = exchange.getRequestHeaders().get(name);
        if (values == null) return fallback;
        if (values.size() != 1 || !values.get(0).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("Invalid context header");
        }
        return values.get(0);
    }

    private void stream(HttpExchange exchange, SessionKey key, String prompt) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        var output = exchange.getResponseBody();
        output.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
        java.util.Iterator<Event> events = null;
        try {
            events =
                    runner.runAsync(
                                    key.userId(),
                                    key.sessionId(),
                                    Content.fromParts(Part.fromText(prompt)),
                                    RunConfig.builder()
                                            .streamingMode(RunConfig.StreamingMode.SSE)
                                            .build())
                            .timeout(120, TimeUnit.SECONDS)
                            .blockingIterable(1)
                            .iterator();
            while (events.hasNext()) {
                Event event = events.next();
                output.write(("data: " + event.toJson() + "\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (RuntimeException failure) {
            output.write(
                    "event: error\ndata: {\"error\":{\"type\":\"InvocationError\",\"message\":\"Agent invocation failed\"}}\n\n"
                            .getBytes(StandardCharsets.UTF_8));
            output.flush();
        } finally {
            // RxJava's blocking Flowable iterator is Disposable; closing the HTTP stream
            // cancels the upstream model subscription instead of leaving work running.
            if (events instanceof Disposable subscription) subscription.dispose();
        }
    }

    private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (exchange.getRequestMethod().equals(method)) return true;
        exchange.getResponseHeaders().set("Allow", method);
        error(exchange, 405, "MethodNotAllowed", "Method not allowed");
        return false;
    }

    private static void error(HttpExchange exchange, int code, String type, String message)
            throws IOException {
        send(exchange, code, Map.of("error", Map.of("type", type, "message", message)));
    }

    private static void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(1);
        executor.shutdownNow();
        invocations.shutdownNow();
    }

    public static void main(String[] args) throws IOException {
        var configured = AgentKitAgentConfiguration.fromEnvironment(System.getenv());
        String bind = System.getenv().getOrDefault("AGENTKIT_BIND_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8000"));
        boolean streaming =
                Boolean.parseBoolean(System.getenv().getOrDefault("AGENTKIT_STREAMING", "false"));
        AgentKitRuntimeExample app;
        try {
            app =
                    new AgentKitRuntimeExample(
                            new InetSocketAddress(bind, port), configured.agent(), streaming);
        } catch (IOException | RuntimeException failure) {
            configured.close();
            throw failure;
        }
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    app.close();
                                    configured.close();
                                }));
        app.start();
        System.out.println("AgentKit Java example listening on " + bind + ":" + app.port());
    }
}
