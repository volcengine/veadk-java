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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentKitRuntimeExampleTest {
    private AgentKitRuntimeExample app;
    private final AtomicInteger calls = new AtomicInteger();
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() throws Exception {
        app =
                new AgentKitRuntimeExample(
                        new InetSocketAddress("127.0.0.1", 0),
                        prompt -> {
                            calls.incrementAndGet();
                            if (prompt.equals("fail"))
                                throw new IllegalStateException("private-credential");
                            return "Answer: " + prompt;
                        });
        app.start();
    }

    @AfterEach
    void stop() {
        app.close();
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                        .timeout(Duration.ofSeconds(2))
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthChecksDoNotInvokeModel() throws Exception {
        for (String path : new String[] {"/ping", "/health", "/readiness", "/liveness"}) {
            assertEquals(200, request("GET", path, "").statusCode());
        }
        assertEquals(
                "pong!", json.readTree(request("GET", "/ping", "").body()).get("status").asText());
        assertEquals(0, calls.get());
    }

    @Test
    void invokeReturnsJsonStringAndPreservesUnicode() throws Exception {
        var response = request("POST", "/invoke", "{\"prompt\":\"你好\"}");
        assertEquals(200, response.statusCode());
        assertEquals("Answer: 你好", json.readTree(response.body()).asText());
        assertTrue(
                response.headers()
                        .firstValue("Content-Type")
                        .orElseThrow()
                        .startsWith("application/json"));
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsMalformedOrMissingPromptsBeforeInvoking() throws Exception {
        for (String body :
                new String[] {
                    "",
                    "null",
                    "[]",
                    "{}",
                    "{",
                    "{\"prompt\":1}",
                    "{\"prompt\":\" \"}",
                    "{\"prompt\":\"ok\"} {}"
                }) {
            assertEquals(400, request("POST", "/invoke", body).statusCode(), body);
        }
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsOversizedRequestsBeforeInvoking() throws Exception {
        assertEquals(
                413,
                request("POST", "/invoke", "x".repeat(AgentKitRuntimeExample.MAX_REQUEST_BYTES + 1))
                        .statusCode());
        assertEquals(0, calls.get());
    }

    @Test
    void enforcesMethodsAndExactPaths() throws Exception {
        var response = request("GET", "/invoke", "");
        assertEquals(405, response.statusCode());
        assertEquals("POST", response.headers().firstValue("Allow").orElseThrow());
        assertEquals(405, request("POST", "/ping", "{}").statusCode());
        assertEquals(404, request("POST", "/invoke/extra", "{}").statusCode());
        assertEquals(0, calls.get());
    }

    @Test
    void doesNotExposeInternalFailureDetails() throws Exception {
        var response = request("POST", "/invoke", "{\"prompt\":\"fail\"}");
        assertEquals(500, response.statusCode());
        assertEquals("InvocationError", json.readTree(response.body()).at("/error/type").asText());
        assertFalse(response.body().contains("private-credential"));
        assertEquals(200, request("POST", "/invoke", "{\"prompt\":\"recovered\"}").statusCode());
    }

    @Test
    void healthChecksAndOverloadResponseRemainAvailableDuringSlowInvocations() throws Exception {
        app.close();
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        app =
                new AgentKitRuntimeExample(
                        new InetSocketAddress("127.0.0.1", 0),
                        prompt -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            try {
                                if (!release.await(15, TimeUnit.SECONDS))
                                    throw new IllegalStateException(
                                            "Timed out waiting for release");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("Interrupted", e);
                            }
                            return "Answer: " + prompt;
                        });
        app.start();
        var pending = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        try {
            for (int i = 0; i < 4; i++) {
                pending.add(
                        client.sendAsync(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://127.0.0.1:"
                                                                + app.port()
                                                                + "/invoke"))
                                        .timeout(Duration.ofSeconds(15))
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        "{\"prompt\":\"slow\"}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString()));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS), "All four invocations must be running");
            for (String path : new String[] {"/ping", "/health", "/readiness", "/liveness"}) {
                assertEquals(200, request("GET", path, "").statusCode(), path);
            }
            var overloaded = request("POST", "/invoke", "{\"prompt\":\"excess\"}");
            assertEquals(503, overloaded.statusCode());
            assertEquals(
                    "ServiceUnavailable",
                    json.readTree(overloaded.body()).at("/error/type").asText());
            assertEquals(4, calls.get(), "An overloaded request must not invoke the model");
        } finally {
            release.countDown();
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                    .get(5, TimeUnit.SECONDS);
        }
        for (var response : pending) {
            assertEquals(200, response.get().statusCode());
            assertEquals("Answer: slow", json.readTree(response.get().body()).asText());
        }
        assertEquals(200, request("POST", "/invoke", "{\"prompt\":\"recovered\"}").statusCode());
        assertEquals(5, calls.get());
    }

    @Test
    void closeInterruptsActiveInvocations() throws Exception {
        app.close();
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        app =
                new AgentKitRuntimeExample(
                        new InetSocketAddress("127.0.0.1", 0),
                        prompt -> {
                            entered.countDown();
                            try {
                                new CountDownLatch(1).await();
                                throw new AssertionError("Invocation unexpectedly released");
                            } catch (InterruptedException e) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("Interrupted", e);
                            }
                        });
        app.start();
        var pending =
                client.sendAsync(
                        HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + app.port() + "/invoke"))
                                .timeout(Duration.ofSeconds(5))
                                .POST(HttpRequest.BodyPublishers.ofString("{\"prompt\":\"slow\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            app.close();
            assertTrue(
                    interrupted.await(2, TimeUnit.SECONDS),
                    "Shutdown must interrupt model workers");
        } finally {
            pending.cancel(true);
        }
    }
}
