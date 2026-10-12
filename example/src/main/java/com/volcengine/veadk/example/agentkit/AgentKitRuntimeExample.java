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
import com.google.adk.agents.LlmAgent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.volcengine.veadk.Runner;
import com.volcengine.veadk.model.ArkLlm;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** Minimal stateless JSON application implementing AgentKit Simple App HTTP routes. */
public final class AgentKitRuntimeExample implements AutoCloseable {
    static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Function<String, String> invoke;

    public AgentKitRuntimeExample(InetSocketAddress address, Function<String, String> invoke)
            throws IOException {
        this.invoke = Objects.requireNonNull(invoke, "invoke must be set");
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
            String result;
            try {
                result = invoke.apply(payload.get("prompt").asText());
            } catch (RuntimeException failure) {
                // Do not return exception details, prompts or credentials to callers.
                error(exchange, 500, "InvocationError", "Agent invocation failed");
                return;
            }
            // AgentKit Simple App serializes the entrypoint return value as JSON.
            send(exchange, 200, result);
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
        server.stop(1);
        executor.shutdownNow();
    }

    public static void main(String[] args) throws IOException {
        String modelId = System.getenv("MODEL_AGENT_NAME");
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException(
                    "Set MODEL_AGENT_NAME to an accessible Ark model ID");
        }
        String bind = System.getenv().getOrDefault("AGENTKIT_BIND_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8000"));
        ArkLlm model = new ArkLlm(modelId);
        LlmAgent agent =
                LlmAgent.builder()
                        .name("agentkit_java_example")
                        .model(model)
                        .instruction("Answer the user's question concisely.")
                        .build();
        AgentKitRuntimeExample app;
        try {
            // A fresh Runner owns request-local in-memory sessions; no cross-request history.
            app =
                    new AgentKitRuntimeExample(
                            new InetSocketAddress(bind, port),
                            prompt -> new Runner(agent).run(prompt));
        } catch (IOException | RuntimeException failure) {
            model.close();
            throw failure;
        }
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    app.close();
                                    model.close();
                                }));
        app.start();
        System.out.println("AgentKit Java example listening on " + bind + ":" + app.port());
    }
}
