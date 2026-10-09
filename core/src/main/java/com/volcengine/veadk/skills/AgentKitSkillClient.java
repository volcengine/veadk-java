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

import com.fasterxml.jackson.databind.JsonNode;
import com.volcengine.veadk.integration.agentkit.AgentKitWrapper;
import com.volcengine.veadk.utils.EnvUtil;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/** Client for remote skill metadata and archives exposed by AgentKit. */
public class AgentKitSkillClient {

    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);

    private final AgentKitWrapper agentKitWrapper;
    private final HttpClient httpClient;
    private final SkillHubClient skillHubClient;

    public AgentKitSkillClient() {
        this(
                new AgentKitWrapper(
                        EnvUtil.getAgentKitManagementHost(),
                        EnvUtil.getAgentKitRegion(),
                        EnvUtil.getAccessKey(),
                        EnvUtil.getSecretKey()),
                HttpClient.newHttpClient(),
                new SkillHubClient());
        String sessionToken = EnvUtil.getSessionToken();
        if (StringUtils.isNotBlank(sessionToken)) {
            agentKitWrapper.setSessionToken(sessionToken);
        }
    }

    public AgentKitSkillClient(AgentKitWrapper agentKitWrapper, HttpClient httpClient) {
        this(agentKitWrapper, httpClient, new SkillHubClient(httpClient));
    }

    AgentKitSkillClient(
            AgentKitWrapper agentKitWrapper, HttpClient httpClient, SkillHubClient skillHubClient) {
        this.agentKitWrapper =
                Objects.requireNonNull(agentKitWrapper, "agentKitWrapper must be set.");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must be set.");
        this.skillHubClient = Objects.requireNonNull(skillHubClient, "skillHubClient must be set.");
    }

    public List<RemoteSkill> listSkills(String skillSourceId) {
        String resolvedSkillSourceId = requireText(skillSourceId, "skillSourceId must be set.");
        if (resolvedSkillSourceId.startsWith("sp-")) {
            return skillHubClient.listSkills(resolvedSkillSourceId);
        }

        JsonNode result = agentKitWrapper.listSkillsBySpaceId(resolvedSkillSourceId);
        JsonNode items = extractItems(result);
        List<RemoteSkill> skills = new ArrayList<>();
        if (!items.isArray()) {
            return skills;
        }

        for (JsonNode item : items) {
            String name = text(item, "Name", "name");
            if (StringUtils.isBlank(name)) {
                continue;
            }
            skills.add(
                    new RemoteSkill(
                            name,
                            text(item, "Description", "description"),
                            text(item, "TosPath", "tos_path", "Path", "path"),
                            resolvedSkillSourceId,
                            text(item, "BucketName", "bucket_name"),
                            text(item, "SkillId", "skill_id", "Id", "id"),
                            text(item, "Slug", "slug"),
                            "skillspace",
                            text(item, "Version", "SkillVersion", "version", "skill_version")));
        }
        return skills;
    }

    public void downloadSkill(RemoteSkill skill, Path targetZip)
            throws IOException, InterruptedException {
        Objects.requireNonNull(skill, "skill must be set.");
        Objects.requireNonNull(targetZip, "targetZip must be set.");
        if (skill.sourceType().filter("skillhub"::equalsIgnoreCase).isPresent()
                || skill.skillSourceId().startsWith("sp-")) {
            skillHubClient.downloadSkill(skill, targetZip);
            return;
        }

        String skillId = skill.id().orElseGet(() -> skillPathPart(skill.path(), 1));
        String skillVersion = skill.versionId().orElseGet(() -> skillPathPart(skill.path(), 2));
        String signedUrl = agentKitWrapper.generateTempTosObjectDownloadUrl(skillId, skillVersion);

        Files.createDirectories(targetZip.toAbsolutePath().getParent());
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(signedUrl))
                        .timeout(DOWNLOAD_TIMEOUT)
                        .GET()
                        .build();
        HttpResponse<byte[]> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(
                    "Failed to download remote skill archive, HTTP status "
                            + response.statusCode());
        }
        Files.write(targetZip, response.body());
    }

    private static JsonNode extractItems(JsonNode node) {
        JsonNode items = node.path("Items");
        if (items.isMissingNode()) {
            items = node.path("items");
        }
        if (items.isMissingNode()) {
            items = node.path("Result").path("Items");
        }
        return items;
    }

    private static String text(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    private static String skillPathPart(String path, int index) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Remote skill is missing path metadata.");
        }
        String[] parts = path.split("/");
        List<String> nonBlankParts = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                nonBlankParts.add(part);
            }
        }
        if (nonBlankParts.size() <= index) {
            throw new IllegalArgumentException("Invalid remote skill path metadata: " + path);
        }
        return nonBlankParts.get(index);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
