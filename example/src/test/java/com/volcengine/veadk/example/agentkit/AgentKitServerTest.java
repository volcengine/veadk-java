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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AgentKitServerTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private int port;

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bootsWithExistingAgentAndServesNativeAndAgentKitRoutes() throws Exception {
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var log = Files.createTempFile("agentkit-server-test-", ".log");
        var pb =
                new ProcessBuilder(
                        System.getProperty("java.home") + "/bin/java",
                        "-cp",
                        System.getProperty("java.class.path"),
                        AgentKitServerExample.class.getName());
        pb.environment().put("PORT", Integer.toString(port));
        pb.environment().put("AGENTKIT_AGENT_CLASS", AgentKitParityTest.Existing.class.getName());
        pb.environment().put("AGENTKIT_BIND_HOST", "127.0.0.1");
        var process = pb.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean ready = false;
            for (int i = 0; i < 100 && process.isAlive(); i++) {
                try {
                    ready = request("GET", "/ping", null).statusCode() == 200;
                } catch (java.io.IOException unavailable) {
                }
                if (ready) break;
                Thread.sleep(100);
            }
            assertTrue(ready, () -> "Server did not start: " + read(log));
            assertEquals("[\"test_agent\"]", request("GET", "/list-apps", null).body());
            for (String path : java.util.List.of("/health", "/readiness", "/liveness"))
                assertEquals(200, request("GET", path, null).statusCode());
            String session = "/apps/test_agent/users/alice/sessions/one";
            assertEquals(200, request("POST", session, "{}").statusCode());
            assertEquals(200, request("GET", session, null).statusCode());
            String body =
                    "{\"appName\":\"test_agent\",\"userId\":\"alice\",\"sessionId\":\"one\",\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"hello\"}]}}";
            for (String path : java.util.List.of("/run", "/run_sse")) {
                var response = request("POST", path, body);
                assertEquals(200, response.statusCode(), response.body());
                assertTrue(response.body().contains("loaded"), response.body());
            }
            var response = request("POST", "/invoke", "{\"prompt\":\"hello\"}");
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("loaded"), response.body());
            assertEquals(400, request("POST", "/invoke", "{\"prompt\":\"\"}").statusCode());
            assertEquals(204, request("DELETE", session, null).statusCode());
            assertEquals(404, request("GET", session, null).statusCode());
        } finally {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }

    private static String read(java.nio.file.Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            return e.toString();
        }
    }
}
