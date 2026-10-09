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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client for remote skill metadata and archives exposed by AgentKit. */
public class AgentKitSkillClient {

    private static final Logger log = LoggerFactory.getLogger(AgentKitSkillClient.class);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_FINDSKILL_ARCHIVE_BYTES = 64 * 1024 * 1024;

    private final AgentKitWrapper agentKitWrapper;
    private final HttpClient httpClient;
    private final SkillHubClient skillHubClient;
    private final String findSkillDownloadUrl;

    public AgentKitSkillClient() {
        this(
                new AgentKitWrapper(
                        EnvUtil.getAgentKitManagementHost(),
                        EnvUtil.getAgentKitRegion(),
                        EnvUtil.getAccessKey(),
                        EnvUtil.getSecretKey()),
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
                new SkillHubClient(),
                EnvUtil.getFindSkillDownloadUrl());
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
        this(agentKitWrapper, httpClient, skillHubClient, EnvUtil.getFindSkillDownloadUrl());
    }

    AgentKitSkillClient(
            AgentKitWrapper agentKitWrapper,
            HttpClient httpClient,
            SkillHubClient skillHubClient,
            String findSkillDownloadUrl) {
        this.agentKitWrapper =
                Objects.requireNonNull(agentKitWrapper, "agentKitWrapper must be set.");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must be set.");
        this.skillHubClient = Objects.requireNonNull(skillHubClient, "skillHubClient must be set.");
        this.findSkillDownloadUrl =
                requireText(findSkillDownloadUrl, "findSkillDownloadUrl must be set.");
    }

    public List<RemoteSkill> listSkills(String skillSourceId) {
        String resolvedSkillSourceId = requireText(skillSourceId, "skillSourceId must be set.");
        if (resolvedSkillSourceId.startsWith("sp-")) {
            return applySkillSpacePolicy(skillHubClient.listSkills(resolvedSkillSourceId));
        }

        JsonNode result = agentKitWrapper.listSkillsBySpaceId(resolvedSkillSourceId);
        JsonNode items = extractItems(result);
        List<RemoteSkill> skills = new ArrayList<>();
        if (!items.isArray()) {
            return applySkillSpacePolicy(skills);
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
        return applySkillSpacePolicy(skills);
    }

    public void downloadSkill(RemoteSkill skill, Path targetZip)
            throws IOException, InterruptedException {
        Objects.requireNonNull(skill, "skill must be set.");
        Objects.requireNonNull(targetZip, "targetZip must be set.");
        if (skill.sourceType().filter("findskill"::equalsIgnoreCase).isPresent()) {
            downloadFindSkill(skill, targetZip);
            return;
        }
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

    private List<RemoteSkill> applySkillSpacePolicy(List<RemoteSkill> skills) {
        Optional<SkillSpacePolicy> policy;
        try {
            policy = SkillSpacePolicy.fromRaw(EnvUtil.getSkillSpacePolicy());
        } catch (SkillSpacePolicy.SkillSpacePolicyException e) {
            log.error(
                    "Invalid {}; remote Skill Space skills are disabled for this session: {}",
                    SkillSpacePolicy.ENV_NAME,
                    e.getMessage());
            return List.of();
        }
        if (policy.isEmpty()) {
            return skills;
        }
        return skills.stream()
                .filter(skill -> policy.get().allows(skill.id().orElse(null)))
                .toList();
    }

    private void downloadFindSkill(RemoteSkill skill, Path targetZip)
            throws IOException, InterruptedException {
        String slug =
                skill.slug()
                        .or(() -> nonBlank(skill.path()))
                        .or(() -> skill.id())
                        .map(value -> StringUtils.strip(value, "/"))
                        .filter(StringUtils::isNotBlank)
                        .orElseThrow(
                                () ->
                                        new IOException(
                                                "Skill Hub skill '"
                                                        + skill.name()
                                                        + "' has no download slug."));
        String url =
                StringUtils.stripEnd(findSkillDownloadUrl, "/")
                        + "/"
                        + encodePathPreservingSlash(slug);
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url)).timeout(DOWNLOAD_TIMEOUT).GET().build();
        HttpResponse<byte[]> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(
                    "Failed to download Skill Hub skill '"
                            + skill.name()
                            + "', HTTP status "
                            + response.statusCode());
        }
        byte[] content = response.body();
        if (content.length > MAX_FINDSKILL_ARCHIVE_BYTES) {
            throw new IOException("Skill Hub skill '" + skill.name() + "' archive exceeds 64 MiB.");
        }
        if (!isZip(content)) {
            throw new IOException(
                    "Skill Hub skill '" + skill.name() + "' download is not a zip archive.");
        }
        Files.createDirectories(targetZip.toAbsolutePath().getParent());
        Files.write(targetZip, content);
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

    private static Optional<String> nonBlank(String value) {
        return StringUtils.isBlank(value) ? Optional.empty() : Optional.of(value);
    }

    private static String encodePathPreservingSlash(String slug) {
        return List.of(slug.split("/", -1)).stream()
                .map(
                        segment ->
                                URLEncoder.encode(segment, StandardCharsets.UTF_8)
                                        .replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    private static boolean isZip(byte[] content) {
        return content.length >= 4
                && content[0] == 'P'
                && content[1] == 'K'
                && ((content[2] == 0x03 && content[3] == 0x04)
                        || (content[2] == 0x05 && content[3] == 0x06));
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
