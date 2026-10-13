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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.adk.agents.BaseAgent;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class AgentKitRuntimeProcessTest {
    @TempDir Path directory;

    public static class ClosingRoot {
        public static final BaseAgent ROOT_AGENT =
                new AgentKitParityTest.TestAgent(
                        c ->
                                Flowable.just(
                                        AgentKitParityTest.event("process reply", true),
                                        AgentKitParityTest.event("process reply", false))) {
                    @Override
                    public Completable close() {
                        return Completable.fromAction(
                                () ->
                                        Files.writeString(
                                                Path.of(System.getenv("TEST_CLOSE_MARKER")),
                                                "closed\n",
                                                StandardOpenOption.CREATE,
                                                StandardOpenOption.APPEND));
                    }
                };
    }

    private ProcessBuilder runtime(Path closed) {
        var process = AgentKitTestJvm.process(AgentKitRuntimeExample.class);
        process.environment().put("AGENTKIT_AGENT_CLASS", ClosingRoot.class.getName());
        process.environment().put("AGENTKIT_BIND_HOST", "127.0.0.1");
        process.environment().put("TEST_CLOSE_MARKER", closed.toString());
        return process;
    }

    private static void stop(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("Runtime did not shut down gracefully");
        }
    }

    @Test
    void mainServesBasicAndStreamingRequestsAndClosesAgentOnShutdown() throws Exception {
        var client = HttpClient.newHttpClient();
        for (boolean streaming : List.of(false, true)) {
            var closed = directory.resolve("closed-" + streaming);
            var log = directory.resolve("runtime-" + streaming + ".log");
            var builder = runtime(closed);
            builder.environment().put("PORT", "0");
            if (streaming) builder.environment().put("AGENTKIT_STREAMING", "true");
            var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                int port = 0;
                var pattern =
                        Pattern.compile(
                                "AgentKit Java example listening on 127\\.0\\.0\\.1:(\\d+)");
                for (int i = 0; i < 100 && process.isAlive(); i++) {
                    var match = pattern.matcher(Files.readString(log));
                    if (match.find()) {
                        port = Integer.parseInt(match.group(1));
                        break;
                    }
                    Thread.sleep(100);
                }
                assertTrue(port > 0, () -> "Runtime did not start: " + read(log));
                var response =
                        client.send(
                                HttpRequest.newBuilder(
                                                URI.create("http://127.0.0.1:" + port + "/invoke"))
                                        .timeout(Duration.ofSeconds(5))
                                        .header("Content-Type", "application/json")
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        "{\"prompt\":\"hello\"}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                if (streaming) {
                    assertTrue(
                            response.headers()
                                    .firstValue("Content-Type")
                                    .orElseThrow()
                                    .startsWith("text/event-stream"));
                    assertTrue(response.body().contains("data: "));
                    assertTrue(response.body().contains("process reply"));
                } else {
                    assertTrue(
                            response.headers()
                                    .firstValue("Content-Type")
                                    .orElseThrow()
                                    .startsWith("application/json"));
                    assertEquals("\"process reply\"", response.body());
                }
            } finally {
                stop(process);
            }
            assertEquals("closed\n", Files.readString(closed));
        }
    }

    @Test
    void startupFailureClosesConfiguredAgent() throws Exception {
        var closed = directory.resolve("closed-on-failure");
        var log = directory.resolve("failure.log");
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var builder = runtime(closed);
            builder.environment().put("PORT", Integer.toString(occupied.getLocalPort()));
            var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(
                        process.waitFor(10, TimeUnit.SECONDS),
                        "Startup failure must terminate the process");
                assertNotEquals(0, process.exitValue());
                assertTrue(Files.readString(log).contains("BindException"));
                assertEquals("closed\n", Files.readString(closed));
            } finally {
                stop(process);
            }
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            return e.toString();
        }
    }
}
