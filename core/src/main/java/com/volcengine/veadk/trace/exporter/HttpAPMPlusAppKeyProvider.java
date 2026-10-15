/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.trace.exporter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volcengine.veadk.security.VolcengineV4Signer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Fetches an APMPlus app key through the signed GetAppKey OpenAPI. */
public final class HttpAPMPlusAppKeyProvider implements APMPlusAppKeyProvider {
    private static final String QUERY = "Action=GetAppKey&Version=2024-07-30";
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String sessionToken;
    private final Duration timeout;
    private final Clock clock;

    public static HttpAPMPlusAppKeyProvider fromEnvironment() {
        boolean byteplus = "byteplus".equalsIgnoreCase(env("CLOUD_PROVIDER", ""));
        String host =
                env(
                        "APMPLUS_OPENAPI_HOST",
                        byteplus ? "open.byteplusapi.com" : "open.volcengineapi.com");
        return new HttpAPMPlusAppKeyProvider(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                URI.create(host.contains("://") ? host : "https://" + host),
                byteplus
                        ? envFirstRequired("BYTEPLUS_ACCESS_KEY", "VOLCENGINE_ACCESS_KEY")
                        : requiredEnv("VOLCENGINE_ACCESS_KEY"),
                byteplus
                        ? envFirstRequired("BYTEPLUS_SECRET_KEY", "VOLCENGINE_SECRET_KEY")
                        : requiredEnv("VOLCENGINE_SECRET_KEY"),
                byteplus
                        ? env("BYTEPLUS_SESSION_TOKEN", env("VOLCENGINE_SESSION_TOKEN", ""))
                        : env("VOLCENGINE_SESSION_TOKEN", env("VOLC_SESSIONTOKEN", "")),
                Duration.ofSeconds(30),
                Clock.systemUTC());
    }

    HttpAPMPlusAppKeyProvider(
            HttpClient client,
            ObjectMapper mapper,
            URI endpoint,
            String accessKey,
            String secretKey,
            String sessionToken,
            Duration timeout,
            Clock clock) {
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.accessKey = requireText(accessKey, "accessKey");
        this.secretKey = requireText(secretKey, "secretKey");
        this.sessionToken = sessionToken == null ? "" : sessionToken.trim();
        this.timeout = Objects.requireNonNull(timeout);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public String getAppKey(String region) throws Exception {
        String resolvedRegion = requireText(region, "region");
        byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        URI uri = URI.create(endpoint.toString().replaceAll("/$", "") + "/?" + QUERY);
        Map<String, String> signed =
                VolcengineV4Signer.sign(
                        uri,
                        QUERY,
                        body,
                        resolvedRegion,
                        "apmplus_server",
                        accessKey,
                        secretKey,
                        sessionToken,
                        clock);
        HttpRequest.Builder request =
                HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .header("X-Apmplus-Region", resolvedRegion.replace('-', '_'))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        signed.forEach(request::header);
        HttpResponse<byte[]> response =
                client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException(
                    "APMPlus GetAppKey request failed with HTTP " + response.statusCode() + ".");
        JsonNode appKey = mapper.readTree(response.body()).path("data").path("app_key");
        if (!appKey.isTextual() || appKey.textValue().isBlank())
            throw new IllegalStateException("APMPlus GetAppKey response is missing data.app_key.");
        return appKey.textValue();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requiredEnv(String name) {
        return requireText(System.getenv(name), name);
    }

    private static String envFirstRequired(String first, String second) {
        String value = env(first, "");
        return value.isBlank() ? requiredEnv(second) : value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " must be set.");
        return value.trim();
    }
}
