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
import com.volcengine.veadk.utils.EnvUtil;
import com.volcengine.veadk.utils.JSONUtil;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.lang3.StringUtils;

/** Client for remote skills exposed by SkillHub. */
public class SkillHubClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_PAGES = 100;
    private static final String CONTENT_TYPE = "application/json";
    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    private static final String ALGORITHM = "HMAC-SHA256";
    private static final DateTimeFormatter X_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final HttpClient httpClient;
    private final Clock clock;
    private final String scheme;
    private final String host;
    private final String region;
    private final String service;
    private final String accessKey;
    private final String secretKey;
    private final String sessionToken;
    private final int pageSize;

    public SkillHubClient() {
        this(HttpClient.newHttpClient());
    }

    SkillHubClient(HttpClient httpClient) {
        this(
                EnvUtil.getSkillHubScheme(),
                EnvUtil.getSkillHubHost(),
                EnvUtil.getSkillHubRegion(),
                EnvUtil.getSkillHubService(),
                null,
                null,
                null,
                EnvUtil.getSkillHubListSkillsPageSize(),
                httpClient,
                Clock.systemUTC());
    }

    SkillHubClient(
            String scheme,
            String host,
            String region,
            String service,
            String accessKey,
            String secretKey,
            String sessionToken,
            int pageSize,
            HttpClient httpClient,
            Clock clock) {
        this.scheme = requireText(scheme, "scheme must be set.");
        this.host = requireText(host, "host must be set.");
        this.region = requireText(region, "region must be set.");
        this.service = requireText(service, "service must be set.");
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.sessionToken = sessionToken;
        this.pageSize = pageSize > 0 ? pageSize : 100;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must be set.");
        this.clock = Objects.requireNonNull(clock, "clock must be set.");
    }

    public List<RemoteSkill> listSkills(String skillSpaceId) {
        String resolvedSkillSpaceId = requireText(skillSpaceId, "skillSourceId must be set.");
        List<RemoteSkill> skills = new ArrayList<>();
        int pageNumber = 1;
        Integer totalCount = null;

        while (totalCount == null || skills.size() < totalCount) {
            if (pageNumber > MAX_PAGES) {
                throw new IllegalStateException(
                        "SkillHub ListSkills pagination exceeded " + MAX_PAGES + " pages.");
            }

            JsonNode response =
                    postJson(
                            "/ListSkills",
                            Map.of(
                                    "PageNumber", pageNumber,
                                    "PageSize", pageSize,
                                    "Filter", Map.of("SkillSpaceId", resolvedSkillSpaceId)));
            if (totalCount == null) {
                totalCount = integer(response, "TotalCount").orElse(null);
                if (totalCount == null) {
                    totalCount = integer(response.path("Result"), "TotalCount").orElse(null);
                }
            }

            JsonNode items = extractItems(response);
            if (!items.isArray() || items.isEmpty()) {
                break;
            }
            int loadedBeforePage = skills.size();
            for (JsonNode item : items) {
                Optional<RemoteSkill> skill = toRemoteSkill(item, resolvedSkillSpaceId);
                skill.ifPresent(skills::add);
            }
            if (skills.size() == loadedBeforePage) {
                break;
            }
            if (totalCount == null && items.size() < pageSize) {
                break;
            }
            pageNumber++;
        }
        return skills;
    }

    public void downloadSkill(RemoteSkill skill, Path targetZip)
            throws IOException, InterruptedException {
        Objects.requireNonNull(skill, "skill must be set.");
        Objects.requireNonNull(targetZip, "targetZip must be set.");
        String skillId =
                skill.id()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "SkillHub skill "
                                                        + skill.name()
                                                        + " is missing skill id."));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("SkillId", skillId);
        requestBody.put("IsPreview", true);
        skill.versionId().ifPresent(versionId -> requestBody.put("SkillVersionId", versionId));

        byte[] content = postBytes("/DownloadSkill", requestBody);
        if (content.length == 0) {
            throw new IOException("SkillHub DownloadSkill returned empty content.");
        }
        Files.createDirectories(targetZip.toAbsolutePath().getParent());
        Files.write(targetZip, content);
    }

    private JsonNode postJson(String path, Map<String, Object> requestBody) {
        try {
            return JSONUtil.parseJson(postBytes(path, requestBody));
        } catch (IOException e) {
            throw new IllegalStateException("SkillHub " + path + " returned invalid JSON.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling SkillHub " + path + ".", e);
        }
    }

    private byte[] postBytes(String path, Map<String, Object> requestBody)
            throws IOException, InterruptedException {
        String body = JSONUtil.toJson(requestBody);
        HttpRequest request =
                signedRequest(path, body)
                        .timeout(REQUEST_TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        HttpResponse<byte[]> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(
                    "SkillHub " + path + " failed, HTTP status " + response.statusCode() + ".");
        }
        return response.body();
    }

    private HttpRequest.Builder signedRequest(String path, String body) {
        String resolvedPath = path.startsWith("/") ? path : "/" + path;
        URI uri = URI.create(scheme + "://" + host + resolvedPath);
        String xDate = X_DATE_FORMATTER.format(clock.instant());
        String shortDate = xDate.substring(0, 8);
        String credentialScope = shortDate + "/" + region + "/" + service + "/request";

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", CONTENT_TYPE);
        headers.put("Host", canonicalHost(uri));
        headers.put("X-Date", xDate);
        headers.put("X-Content-Sha256", UNSIGNED_PAYLOAD);
        String resolvedSessionToken = resolvedSessionToken();
        if (StringUtils.isNotBlank(resolvedSessionToken)) {
            headers.put("X-Security-Token", resolvedSessionToken);
        }

        String signature =
                signature(
                        resolvedPath,
                        headers,
                        xDate,
                        shortDate,
                        credentialScope,
                        resolvedSecretKey());
        String authorization =
                ALGORITHM
                        + " Credential="
                        + resolvedAccessKey()
                        + "/"
                        + credentialScope
                        + ", SignedHeaders="
                        + signedHeaders(headers)
                        + ", Signature="
                        + signature;

        HttpRequest.Builder builder =
                HttpRequest.newBuilder(uri)
                        .header("Accept", CONTENT_TYPE)
                        .header("Authorization", authorization);
        headers.forEach(
                (name, value) -> {
                    if (!"Host".equalsIgnoreCase(name)) {
                        builder.header(name, value);
                    }
                });
        return builder;
    }

    private String signature(
            String path,
            Map<String, String> headers,
            String xDate,
            String shortDate,
            String credentialScope,
            String resolvedSecretKey) {
        String canonicalRequest =
                String.join(
                        "\n",
                        "POST",
                        normalizePath(path),
                        "",
                        canonicalHeaders(headers),
                        signedHeaders(headers),
                        UNSIGNED_PAYLOAD);
        String stringToSign =
                String.join(
                        "\n",
                        ALGORITHM,
                        xDate,
                        credentialScope,
                        sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        return hmacHex(signingKey(resolvedSecretKey, shortDate, region, service), stringToSign);
    }

    private static String canonicalHeaders(Map<String, String> headers) {
        Map<String, String> normalized = normalizedSignedHeaders(headers);
        StringBuilder builder = new StringBuilder();
        normalized.forEach(
                (name, value) ->
                        builder.append(name).append(':').append(value.trim()).append('\n'));
        return builder.toString();
    }

    private static String signedHeaders(Map<String, String> headers) {
        return String.join(";", normalizedSignedHeaders(headers).keySet());
    }

    private static Map<String, String> normalizedSignedHeaders(Map<String, String> headers) {
        Map<String, String> normalized = new TreeMap<>();
        headers.forEach(
                (name, value) -> {
                    String lowerName = name.toLowerCase(Locale.ROOT);
                    if (isSignedHeader(name)) {
                        normalized.put(lowerName, value);
                    }
                });
        return normalized;
    }

    private static boolean isSignedHeader(String name) {
        return "Content-Type".equalsIgnoreCase(name)
                || "Content-Md5".equalsIgnoreCase(name)
                || "Host".equalsIgnoreCase(name)
                || name.startsWith("X-");
    }

    private static byte[] signingKey(String secretKey, String date, String region, String service) {
        byte[] dateKey = hmac(secretKey.getBytes(StandardCharsets.UTF_8), date);
        byte[] regionKey = hmac(dateKey, region);
        byte[] serviceKey = hmac(regionKey, service);
        return hmac(serviceKey, "request");
    }

    private static String hmacHex(byte[] key, String data) {
        return HexFormat.of().formatHex(hmac(key, data));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 signature.", e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate SHA-256 hash.", e);
        }
    }

    private static String canonicalHost(URI uri) {
        int port = uri.getPort();
        if (port < 0
                || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
                || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443)) {
            return uri.getHost();
        }
        return uri.getHost() + ":" + port;
    }

    private static String normalizePath(String path) {
        String[] parts = path.split("/", -1);
        List<String> encodedParts = new ArrayList<>();
        for (String part : parts) {
            encodedParts.add(percentEncode(part));
        }
        return String.join("/", encodedParts);
    }

    private static String percentEncode(String value) {
        StringBuilder builder = new StringBuilder();
        for (byte rawByte : value.getBytes(StandardCharsets.UTF_8)) {
            int b = rawByte & 0xff;
            if ((b >= 'a' && b <= 'z')
                    || (b >= 'A' && b <= 'Z')
                    || (b >= '0' && b <= '9')
                    || b == '-'
                    || b == '_'
                    || b == '.'
                    || b == '~') {
                builder.append((char) b);
            } else {
                builder.append('%');
                builder.append("0123456789ABCDEF".charAt((b >> 4) & 0xf));
                builder.append("0123456789ABCDEF".charAt(b & 0xf));
            }
        }
        return builder.toString();
    }

    private Optional<RemoteSkill> toRemoteSkill(JsonNode item, String skillSpaceId) {
        String name = text(item, "Name", "name");
        String skillId = text(item, "Id", "SkillId", "id", "skill_id");
        if (StringUtils.isAnyBlank(name, skillId)) {
            return Optional.empty();
        }
        String slug = text(item, "Slug", "slug");
        String description =
                Optional.of(nestedText(item, "Metadata", "DisplayDescription"))
                        .filter(StringUtils::isNotBlank)
                        .orElseGet(() -> text(item, "Description", "description"));
        String versionId =
                Optional.of(nestedText(item, "RelatedSkillVersion", "Id"))
                        .filter(StringUtils::isNotBlank)
                        .orElseGet(() -> nestedText(item, "LatestVersionStatus", "VersionId"));
        return Optional.of(
                new RemoteSkill(
                        name,
                        description,
                        StringUtils.isNotBlank(slug) ? slug : skillId,
                        skillSpaceId,
                        "",
                        skillId,
                        slug,
                        "skillhub",
                        versionId));
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

    private static Optional<Integer> integer(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isIntegralNumber()) {
            return Optional.of(value.asInt());
        }
        if (value.isTextual() && StringUtils.isNumeric(value.asText())) {
            return Optional.of(Integer.parseInt(value.asText()));
        }
        return Optional.empty();
    }

    private static String nestedText(JsonNode node, String objectField, String fieldName) {
        JsonNode value = node.path(objectField).path(fieldName);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
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

    private String resolvedAccessKey() {
        return StringUtils.isBlank(accessKey) ? EnvUtil.getAccessKey() : accessKey;
    }

    private String resolvedSecretKey() {
        return StringUtils.isBlank(secretKey) ? EnvUtil.getSecretKey() : secretKey;
    }

    private String resolvedSessionToken() {
        return StringUtils.isBlank(sessionToken) ? EnvUtil.getSessionToken() : sessionToken;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
