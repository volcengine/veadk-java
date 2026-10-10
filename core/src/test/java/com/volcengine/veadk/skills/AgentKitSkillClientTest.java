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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.volcengine.veadk.integration.agentkit.AgentKitWrapper;
import com.volcengine.veadk.utils.JSONUtil;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class AgentKitSkillClientTest {

    @Test
    void listSkillsParsesLegacySkillSpaceResponse() throws Exception {
        AgentKitWrapper wrapper = mock(AgentKitWrapper.class);
        when(wrapper.listSkillsBySpaceId("ss-test"))
                .thenReturn(
                        JSONUtil.parseJson(
                                """
                                {
                                  "Items": [
                                    {
                                      "Name": "alpha-skill",
                                      "Description": "Alpha skill.",
                                      "TosPath": "skills/skill-alpha/v1/alpha.zip",
                                      "BucketName": "bucket-a",
                                      "SkillId": "skill-alpha",
                                      "Version": "v1"
                                    }
                                  ]
                                }
                                """));

        AgentKitSkillClient client = new AgentKitSkillClient(wrapper, HttpClient.newHttpClient());

        List<RemoteSkill> skills = client.listSkills("ss-test");

        assertThat(skills).hasSize(1);
        RemoteSkill skill = skills.get(0);
        assertThat(skill.name()).isEqualTo("alpha-skill");
        assertThat(skill.description()).isEqualTo("Alpha skill.");
        assertThat(skill.path()).isEqualTo("skills/skill-alpha/v1/alpha.zip");
        assertThat(skill.bucketName()).contains("bucket-a");
        assertThat(skill.id()).contains("skill-alpha");
        assertThat(skill.versionId()).contains("v1");
    }

    @Test
    void listSkillsAppliesSkillSpacePolicy() throws Exception {
        AgentKitWrapper wrapper = mock(AgentKitWrapper.class);
        when(wrapper.listSkillsBySpaceId("ss-test"))
                .thenReturn(
                        JSONUtil.parseJson(
                                """
                                {
                                  "Items": [
                                    {"Name": "alpha-skill", "SkillId": "skill-alpha"},
                                    {"Name": "beta-skill", "SkillId": "skill-beta"}
                                  ]
                                }
                                """));
        AgentKitSkillClient client = new AgentKitSkillClient(wrapper, HttpClient.newHttpClient());

        try (MockedStatic<com.volcengine.veadk.utils.EnvUtil> envUtilMock =
                mockStatic(com.volcengine.veadk.utils.EnvUtil.class)) {
            envUtilMock
                    .when(com.volcengine.veadk.utils.EnvUtil::getSkillSpacePolicy)
                    .thenReturn("{\"mode\":\"allow\",\"ids\":[\"skill-beta\"]}");

            List<RemoteSkill> skills = client.listSkills("ss-test");

            assertThat(skills).extracting(RemoteSkill::name).containsExactly("beta-skill");
        }
    }

    @Test
    void invalidSkillSpacePolicyDisablesRemoteSkills() throws Exception {
        AgentKitWrapper wrapper = mock(AgentKitWrapper.class);
        when(wrapper.listSkillsBySpaceId("ss-test"))
                .thenReturn(
                        JSONUtil.parseJson(
                                """
                                {"Items": [{"Name": "alpha-skill", "SkillId": "skill-alpha"}]}
                                """));
        AgentKitSkillClient client = new AgentKitSkillClient(wrapper, HttpClient.newHttpClient());

        try (MockedStatic<com.volcengine.veadk.utils.EnvUtil> envUtilMock =
                mockStatic(com.volcengine.veadk.utils.EnvUtil.class)) {
            envUtilMock
                    .when(com.volcengine.veadk.utils.EnvUtil::getSkillSpacePolicy)
                    .thenReturn("{\"mode\":\"allow\",\"ids\":[\" skill-alpha\"]}");

            assertThat(client.listSkills("ss-test")).isEmpty();
        }
    }

    @Test
    void downloadSkillUsesGeneratedSignedUrl(@TempDir Path tempDir) throws Exception {
        HttpServer server = startDownloadServer("archive-content");
        try {
            AgentKitWrapper wrapper = mock(AgentKitWrapper.class);
            when(wrapper.generateTempTosObjectDownloadUrl("skill-alpha", "v1"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/skill.zip");
            AgentKitSkillClient client =
                    new AgentKitSkillClient(wrapper, HttpClient.newHttpClient());
            RemoteSkill skill =
                    new RemoteSkill(
                            "alpha-skill",
                            "Alpha skill.",
                            "skills/skill-alpha/v1/alpha.zip",
                            "ss-test",
                            "bucket",
                            "skill-alpha",
                            null,
                            "skillspace",
                            "v1");
            Path zipPath = tempDir.resolve("alpha.zip");

            client.downloadSkill(skill, zipPath);

            assertThat(Files.readString(zipPath)).isEqualTo("archive-content");
            verify(wrapper).generateTempTosObjectDownloadUrl("skill-alpha", "v1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadSkillFailureIncludesRemoteContext(@TempDir Path tempDir) throws Exception {
        HttpServer server = startDownloadServer(403, "denied");
        try {
            AgentKitWrapper wrapper = mock(AgentKitWrapper.class);
            when(wrapper.generateTempTosObjectDownloadUrl("skill-alpha", "v1"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/skill.zip");
            AgentKitSkillClient client =
                    new AgentKitSkillClient(wrapper, HttpClient.newHttpClient());
            RemoteSkill skill =
                    new RemoteSkill(
                            "alpha-skill",
                            "Alpha skill.",
                            "skills/skill-alpha/v1/alpha.zip",
                            "ss-test",
                            "bucket",
                            "skill-alpha",
                            null,
                            "skillspace",
                            "v1");

            assertThatThrownBy(() -> client.downloadSkill(skill, tempDir.resolve("alpha.zip")))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("alpha-skill")
                    .hasMessageContaining("ss-test")
                    .hasMessageContaining("HTTP status 403")
                    .hasMessageContaining("denied")
                    .hasMessageContaining("TOS download permissions");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listSkillsDelegatesSkillHubSource() {
        SkillHubClient skillHubClient = mock(SkillHubClient.class);
        RemoteSkill skill =
                new RemoteSkill(
                        "hub-skill",
                        "Hub skill.",
                        "hub-skill",
                        "sp-test",
                        null,
                        "skill-hub",
                        "hub-skill",
                        "skillhub",
                        "version-1");
        when(skillHubClient.listSkills("sp-test")).thenReturn(List.of(skill));
        AgentKitSkillClient client =
                new AgentKitSkillClient(
                        mock(AgentKitWrapper.class), HttpClient.newHttpClient(), skillHubClient);

        List<RemoteSkill> skills = client.listSkills("sp-test");

        assertThat(skills).containsExactly(skill);
        verify(skillHubClient).listSkills("sp-test");
    }

    @Test
    void downloadSkillDelegatesSkillHubSource(@TempDir Path tempDir) throws Exception {
        SkillHubClient skillHubClient = mock(SkillHubClient.class);
        AgentKitSkillClient client =
                new AgentKitSkillClient(
                        mock(AgentKitWrapper.class), HttpClient.newHttpClient(), skillHubClient);
        RemoteSkill skill =
                new RemoteSkill(
                        "hub-skill",
                        "Hub skill.",
                        "hub-skill",
                        "sp-test",
                        null,
                        "skill-hub",
                        "hub-skill",
                        "skillhub",
                        "version-1");
        Path zipPath = tempDir.resolve("hub.zip");

        client.downloadSkill(skill, zipPath);

        verify(skillHubClient).downloadSkill(skill, zipPath);
    }

    @Test
    void downloadFindSkillUsesPublicSlug(@TempDir Path tempDir) throws Exception {
        byte[] zipBody = zipHeaderOnly();
        HttpServer server = startDownloadServer(zipBody);
        try {
            AgentKitSkillClient client =
                    new AgentKitSkillClient(
                            mock(AgentKitWrapper.class),
                            HttpClient.newHttpClient(),
                            mock(SkillHubClient.class),
                            "http://127.0.0.1:"
                                    + server.getAddress().getPort()
                                    + "/v1/skills/download");
            RemoteSkill skill =
                    new RemoteSkill(
                            "public-skill",
                            "Public skill.",
                            "",
                            "findskill",
                            null,
                            null,
                            "team/public-skill",
                            "findskill",
                            "v1");
            Path zipPath = tempDir.resolve("public.zip");

            client.downloadSkill(skill, zipPath);

            assertThat(Files.readAllBytes(zipPath)).isEqualTo(zipBody);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadFindSkillFailureIncludesSlugAndBody(@TempDir Path tempDir) throws Exception {
        HttpServer server = startDownloadServer(404, "missing public skill");
        try {
            AgentKitSkillClient client =
                    new AgentKitSkillClient(
                            mock(AgentKitWrapper.class),
                            HttpClient.newHttpClient(),
                            mock(SkillHubClient.class),
                            "http://127.0.0.1:"
                                    + server.getAddress().getPort()
                                    + "/v1/skills/download");
            RemoteSkill skill =
                    new RemoteSkill(
                            "public-skill",
                            "Public skill.",
                            "",
                            "findskill",
                            null,
                            null,
                            "team/public-skill",
                            "findskill",
                            "v1");

            assertThatThrownBy(() -> client.downloadSkill(skill, tempDir.resolve("public.zip")))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("public-skill")
                    .hasMessageContaining("team/public-skill")
                    .hasMessageContaining("HTTP status 404")
                    .hasMessageContaining("missing public skill")
                    .hasMessageContaining("findskill slug");
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startDownloadServer(String body) throws IOException {
        return startDownloadServer(200, body);
    }

    private static HttpServer startDownloadServer(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/skill.zip", exchange -> respond(exchange, status, body));
        server.createContext(
                "/v1/skills/download/team/public-skill",
                exchange -> respond(exchange, status, body));
        server.start();
        return server;
    }

    private static HttpServer startDownloadServer(byte[] body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/skills/download/team/public-skill", exchange -> respond(exchange, body));
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void respond(HttpExchange exchange, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static byte[] zipHeaderOnly() {
        return new byte[] {'P', 'K', 0x05, 0x06, 0, 0, 0, 0};
    }
}
