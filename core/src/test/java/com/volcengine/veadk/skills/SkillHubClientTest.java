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
package com.volcengine.veadk.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillHubClientTest {

    @Test
    void listSkillsPaginatesAndParsesSkillHubResponse() throws Exception {
        List<String> requestBodies = new ArrayList<>();
        HttpServer server =
                startServer(
                        exchange -> {
                            String body =
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8);
                            requestBodies.add(body);
                            String response =
                                    body.contains("\"PageNumber\":1")
                                            ? """
                                            {
                                              "TotalCount": 2,
                                              "Items": [
                                                {
                                                  "Name": "hub-alpha",
                                                  "Id": "skill-alpha",
                                                  "Slug": "hub-alpha",
                                                  "Metadata": {
                                                    "DisplayDescription": "Alpha from hub."
                                                  },
                                                  "RelatedSkillVersion": {
                                                    "Id": "version-alpha"
                                                  }
                                                }
                                              ]
                                            }
                                            """
                                            : """
                                            {
                                              "TotalCount": 2,
                                              "Items": [
                                                {
                                                  "Name": "hub-beta",
                                                  "SkillId": "skill-beta",
                                                  "Description": "Beta from hub.",
                                                  "LatestVersionStatus": {
                                                    "VersionId": "version-beta"
                                                  }
                                                }
                                              ]
                                            }
                                            """;
                            respond(exchange, 200, response.getBytes(StandardCharsets.UTF_8));
                        });
        try {
            SkillHubClient client = skillHubClient(server);

            List<RemoteSkill> skills = client.listSkills("sp-test");

            assertThat(requestBodies).hasSize(2);
            assertThat(requestBodies.get(0)).contains("\"SkillSpaceId\":\"sp-test\"");
            assertThat(requestBodies.get(1)).contains("\"PageNumber\":2");
            assertThat(skills).hasSize(2);
            assertThat(skills.get(0).name()).isEqualTo("hub-alpha");
            assertThat(skills.get(0).description()).isEqualTo("Alpha from hub.");
            assertThat(skills.get(0).path()).isEqualTo("hub-alpha");
            assertThat(skills.get(0).sourceType()).contains("skillhub");
            assertThat(skills.get(0).versionId()).contains("version-alpha");
            assertThat(skills.get(1).name()).isEqualTo("hub-beta");
            assertThat(skills.get(1).id()).contains("skill-beta");
            assertThat(skills.get(1).versionId()).contains("version-beta");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadSkillWritesArchiveAndSignsUnsignedPayload(@TempDir Path tempDir) throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>();
        AtomicReference<String> payloadHashRef = new AtomicReference<>();
        AtomicReference<String> authRef = new AtomicReference<>();
        AtomicReference<String> dateRef = new AtomicReference<>();
        HttpServer server =
                startServer(
                        exchange -> {
                            bodyRef.set(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                            payloadHashRef.set(
                                    exchange.getRequestHeaders().getFirst("X-Content-Sha256"));
                            authRef.set(exchange.getRequestHeaders().getFirst("Authorization"));
                            dateRef.set(exchange.getRequestHeaders().getFirst("X-Date"));
                            respond(exchange, 200, "zip-bytes".getBytes(StandardCharsets.UTF_8));
                        });
        try {
            SkillHubClient client = skillHubClient(server);
            RemoteSkill skill =
                    new RemoteSkill(
                            "hub-alpha",
                            "Alpha from hub.",
                            "hub-alpha",
                            "sp-test",
                            null,
                            "skill-alpha",
                            "hub-alpha",
                            "skillhub",
                            "version-alpha");
            Path zipPath = tempDir.resolve("hub-alpha.zip");

            client.downloadSkill(skill, zipPath);

            assertThat(Files.readString(zipPath)).isEqualTo("zip-bytes");
            assertThat(bodyRef.get()).contains("\"SkillId\":\"skill-alpha\"");
            assertThat(bodyRef.get()).contains("\"SkillVersionId\":\"version-alpha\"");
            assertThat(bodyRef.get()).contains("\"IsPreview\":true");
            assertThat(payloadHashRef.get()).isEqualTo("UNSIGNED-PAYLOAD");
            assertThat(dateRef.get()).isEqualTo("20261009T000000Z");
            assertThat(authRef.get())
                    .startsWith(
                            "HMAC-SHA256 Credential=ak/20261009/cn-test/skillhub/request,"
                                    + " SignedHeaders=");
            assertThat(authRef.get()).contains("Signature=");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listSkillsHttpFailureIncludesSkillHubContext() throws Exception {
        HttpServer server =
                startServer(
                        exchange ->
                                respond(
                                        exchange,
                                        403,
                                        "permission denied".getBytes(StandardCharsets.UTF_8)));
        try {
            SkillHubClient client = skillHubClient(server);

            assertThatThrownBy(() -> client.listSkills("sp-test"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseInstanceOf(IOException.class)
                    .hasMessageContaining("/ListSkills")
                    .hasMessageContaining("HTTP status 403")
                    .hasMessageContaining("permission denied")
                    .hasMessageContaining("SKILLHUB_HOST/SKILLHUB_REGION")
                    .hasMessageContaining("SkillHub permissions");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadSkillEmptyContentIncludesSkillContext(@TempDir Path tempDir) throws Exception {
        HttpServer server = startServer(exchange -> respond(exchange, 200, new byte[0]));
        try {
            SkillHubClient client = skillHubClient(server);
            RemoteSkill skill =
                    new RemoteSkill(
                            "hub-alpha",
                            "Alpha from hub.",
                            "hub-alpha",
                            "sp-test",
                            null,
                            "skill-alpha",
                            "hub-alpha",
                            "skillhub",
                            "version-alpha");

            assertThatThrownBy(() -> client.downloadSkill(skill, tempDir.resolve("hub-alpha.zip")))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("hub-alpha")
                    .hasMessageContaining("sp-test")
                    .hasMessageContaining("published archive");
        } finally {
            server.stop(0);
        }
    }

    private static SkillHubClient skillHubClient(HttpServer server) {
        return new SkillHubClient(
                "http",
                "127.0.0.1:" + server.getAddress().getPort(),
                "cn-test",
                "skillhub",
                "ak",
                "sk",
                "session-token",
                1,
                HttpClient.newHttpClient(),
                Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC));
    }

    private static HttpServer startServer(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ListSkills", handler::handle);
        server.createContext("/DownloadSkill", handler::handle);
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, byte[] bytes)
            throws IOException {
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
