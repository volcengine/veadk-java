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
package com.volcengine.veadk.a2a;

import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.memory.ShortTermMemory;
import io.a2a.server.ServerCallContext;
import io.a2a.server.auth.UnauthenticatedUser;
import io.a2a.server.events.InMemoryQueueManager;
import io.a2a.server.requesthandlers.DefaultRequestHandler;
import io.a2a.server.tasks.InMemoryTaskStore;
import io.a2a.spec.AgentCard;
import io.a2a.transport.rest.handler.RestHandler;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Embedded REST A2A server exposing an existing VeADK {@link Agent}. */
public final class VeA2AServer implements AutoCloseable {

    private static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;

    private final AgentCard agentCard;
    private final RestHandler restHandler;
    private final ExecutorService executor;
    private HttpServer server;

    public VeA2AServer(Agent agent, String url, String appName, ShortTermMemory shortTermMemory) {
        this(agent, url, appName, shortTermMemory, Duration.ofSeconds(60));
    }

    public VeA2AServer(
            Agent agent,
            String url,
            String appName,
            ShortTermMemory shortTermMemory,
            Duration blockingTimeout) {
        Objects.requireNonNull(agent, "agent must be set.");
        Objects.requireNonNull(shortTermMemory, "shortTermMemory must be set.");
        requirePositiveSeconds(blockingTimeout);
        this.agentCard = AgentCards.fromAgent(agent, requireText(url, "url must be set."));
        this.executor = Executors.newCachedThreadPool(new DaemonThreadFactory("veadk-a2a"));

        VeA2AExecutor agentExecutor =
                new VeA2AExecutor(
                        agent,
                        requireText(appName, "appName must be set."),
                        shortTermMemory.sessionService(),
                        new InMemoryArtifactService(),
                        agent.longTermMemoryService().orElseGet(InMemoryMemoryService::new),
                        blockingTimeout);
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        InMemoryQueueManager queueManager = new InMemoryQueueManager(taskStore);
        DefaultRequestHandler requestHandler =
                agentExecutor.requestHandler(taskStore, queueManager, executor);
        this.restHandler = new RestHandler(agentCard, requestHandler, executor);
    }

    public AgentCard agentCard() {
        return agentCard;
    }

    public synchronized VeA2AServer start(String host, int port) throws IOException {
        if (server != null) {
            throw new IllegalStateException("A2A server is already started.");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535.");
        }
        HttpServer created =
                HttpServer.create(
                        new InetSocketAddress(requireText(host, "host must be set."), port), 0);
        created.createContext("/", this::handle);
        created.setExecutor(executor);
        created.start();
        server = created;
        return this;
    }

    public synchronized int port() {
        if (server == null) {
            throw new IllegalStateException("A2A server is not started.");
        }
        return server.getAddress().getPort();
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            ServerCallContext context =
                    new ServerCallContext(
                            UnauthenticatedUser.INSTANCE, requestState(exchange), Set.of());
            RestHandler.HTTPRestResponse response;
            if ("GET".equals(method) && "/.well-known/agent-card.json".equals(path)) {
                response = restHandler.getAgentCard();
            } else if ("POST".equals(method) && "/v1/message:send".equals(path)) {
                response = restHandler.sendMessage(readBody(exchange), context);
            } else if ("POST".equals(method) && "/v1/message:stream".equals(path)) {
                response = restHandler.sendStreamingMessage(readBody(exchange), context);
            } else if ("GET".equals(method) && path.matches("/v1/tasks/[^/]+")) {
                response =
                        restHandler.getTask(
                                lastSegment(path),
                                historyLength(exchange.getRequestURI()),
                                context);
            } else if ("POST".equals(method) && path.matches("/v1/tasks/[^/]+:cancel")) {
                response = restHandler.cancelTask(taskIdBeforeSuffix(path, ":cancel"), context);
            } else {
                write(exchange, 404, "application/json", "{\"error\":\"not found\"}");
                return;
            }
            if (response instanceof RestHandler.HTTPRestStreamingResponse streaming) {
                writeStreaming(exchange, streaming);
            } else {
                write(
                        exchange,
                        response.getStatusCode(),
                        response.getContentType(),
                        response.getBody());
            }
        } catch (RequestTooLargeException e) {
            write(exchange, 413, "application/json", "{\"error\":\"request too large\"}");
        } catch (Exception e) {
            write(exchange, 500, "application/json", "{\"error\":\"internal server error\"}");
        } finally {
            exchange.close();
        }
    }

    private static Map<String, Object> requestState(HttpExchange exchange) {
        Map<String, Object> state = new LinkedHashMap<>();
        exchange.getRequestHeaders()
                .forEach((key, values) -> state.put(key.toLowerCase(), String.join(",", values)));
        return state;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            byte[] bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) {
                throw new RequestTooLargeException();
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static int historyLength(URI uri) {
        String query = uri.getRawQuery();
        if (query == null) {
            return 0;
        }
        for (String entry : query.split("&")) {
            String[] pair = entry.split("=", 2);
            if (pair.length == 2 && "historyLength".equals(pair[0])) {
                try {
                    return Math.max(0, Integer.parseInt(pair[1]));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String taskIdBeforeSuffix(String path, String suffix) {
        String segment = lastSegment(path);
        return segment.substring(0, segment.length() - suffix.length());
    }

    private static void writeStreaming(
            HttpExchange exchange, RestHandler.HTTPRestStreamingResponse response)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(response.getStatusCode(), 0);
        java.io.OutputStream output = exchange.getResponseBody();
        response.getPublisher()
                .subscribe(
                        new java.util.concurrent.Flow.Subscriber<>() {
                            private java.util.concurrent.Flow.Subscription subscription;

                            @Override
                            public void onSubscribe(
                                    java.util.concurrent.Flow.Subscription subscription) {
                                this.subscription = subscription;
                                subscription.request(1);
                            }

                            @Override
                            public void onNext(String item) {
                                try {
                                    output.write(
                                            ("data: " + item + "\n\n")
                                                    .getBytes(StandardCharsets.UTF_8));
                                    output.flush();
                                    subscription.request(1);
                                } catch (IOException e) {
                                    subscription.cancel();
                                }
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                try {
                                    output.close();
                                } catch (IOException ignored) {
                                    // Connection is already unusable.
                                }
                            }

                            @Override
                            public void onComplete() {
                                try {
                                    output.close();
                                } catch (IOException ignored) {
                                    // Response is already closed.
                                }
                            }
                        });
    }

    private static void write(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = Objects.requireNonNullElse(body, "").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .set("Content-Type", Objects.requireNonNullElse(contentType, "application/json"));
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static int requirePositiveSeconds(Duration duration) {
        Objects.requireNonNull(duration, "blockingTimeout must be set.");
        long seconds = duration.toSeconds();
        if (seconds < 1 || seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "blockingTimeout must be between 1 second and 68 years.");
        }
        return (int) seconds;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private static final class RequestTooLargeException extends IOException {}

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger index = new AtomicInteger();

        private DaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
