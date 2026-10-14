/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volcengine.veadk.security.VolcengineV4Signer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Signed HTTP implementation of the Identity APIs used by VeADK authorization. */
public final class HttpIdentityClient implements IdentityClient {
    private static final String VERSION = "2025-10-30";
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final String sessionToken;
    private final Duration timeout;
    private final Clock clock;

    public HttpIdentityClient(String endpoint, String region, String accessKey, String secretKey) {
        this(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                URI.create(endpoint),
                region,
                accessKey,
                secretKey,
                "",
                Duration.ofSeconds(50),
                Clock.systemUTC());
    }

    HttpIdentityClient(
            HttpClient httpClient,
            ObjectMapper mapper,
            URI endpoint,
            String region,
            String accessKey,
            String secretKey,
            String sessionToken,
            Duration timeout,
            Clock clock) {
        this.httpClient = Objects.requireNonNull(httpClient);
        this.mapper = Objects.requireNonNull(mapper);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.region = requireText(region, "region");
        this.accessKey = requireText(accessKey, "accessKey");
        this.secretKey = requireText(secretKey, "secretKey");
        this.sessionToken = sessionToken == null ? "" : sessionToken;
        this.timeout = Objects.requireNonNull(timeout);
        this.clock = Objects.requireNonNull(clock);
    }

    public static HttpIdentityClient fromEnvironment() {
        String region = env("VEIDENTITY_REGION", env("REGION", "cn-beijing"));
        boolean byteplus = "byteplus".equalsIgnoreCase(env("CLOUD_PROVIDER", ""));
        String host =
                env(
                        "IDENTITY_OPENAPI_HOST",
                        byteplus ? "id." + region + ".byteplusapi.com" : "open.volcengineapi.com");
        String scheme = env("IDENTITY_OPENAPI_SCHEME", "https");
        return new HttpIdentityClient(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                URI.create(host.contains("://") ? host : scheme + "://" + host),
                region,
                byteplus
                        ? envFirstRequired("BYTEPLUS_ACCESS_KEY", "VOLCENGINE_ACCESS_KEY")
                        : envRequired("VOLCENGINE_ACCESS_KEY"),
                byteplus
                        ? envFirstRequired("BYTEPLUS_SECRET_KEY", "VOLCENGINE_SECRET_KEY")
                        : envRequired("VOLCENGINE_SECRET_KEY"),
                byteplus
                        ? env("BYTEPLUS_SESSION_TOKEN", env("VOLCENGINE_SESSION_TOKEN", ""))
                        : env("VOLCENGINE_SESSION_TOKEN", ""),
                Duration.ofSeconds(50),
                Clock.systemUTC());
    }

    @Override
    public WorkloadToken getWorkloadToken(String workloadName, String userId, String userToken)
            throws Exception {
        String action;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Name", requireText(workloadName, "workloadName"));
        if (userToken != null && !userToken.isBlank()) {
            action = "GetWorkloadAccessTokenForJWT";
            body.put("UserToken", userToken);
        } else if (userId != null && !userId.isBlank()) {
            action = "GetWorkloadAccessTokenForUserId";
            body.put("UserId", userId);
        } else {
            action = "GetWorkloadAccessToken";
        }
        JsonNode response = post("/" + action + "/" + VERSION + "/id/post/application_json/", body);
        String token = requiredResponseText(response, "WorkloadAccessToken");
        String expiresAt = requiredResponseText(response, "ExpiresAt");
        return new WorkloadToken(
                token, Instant.from(DateTimeFormatter.ISO_INSTANT.parse(expiresAt)));
    }

    @Override
    public boolean checkPermission(PermissionRequest request) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("NamespaceName", request.namespace());
        body.put("Principal", principal(request.principal()));
        body.put("Operation", principal(request.operation()));
        body.put("Resource", principal(request.resource()));
        body.put(
                "OriginalCallers",
                request.originalCallers().stream().map(HttpIdentityClient::principal).toList());
        JsonNode response =
                post("/CheckPermission/" + VERSION + "/id/post/application_json/", body);
        JsonNode allowed = response.get("Allowed");
        if (allowed == null || !allowed.isBoolean())
            throw new IllegalStateException("Identity response is missing Allowed.");
        return allowed.booleanValue();
    }

    @Override
    public String getApiKey(String providerName, String identityToken) throws Exception {
        JsonNode response =
                post(
                        "/GetResourceApiKey/" + VERSION + "/id/post/application_json/",
                        Map.of(
                                "ProviderName",
                                requireText(providerName, "providerName"),
                                "IdentityToken",
                                requireText(identityToken, "identityToken")));
        return requiredResponseText(response, "ApiKey");
    }

    @Override
    public OAuth2Credential getOAuth2Credential(
            String providerName,
            String identityToken,
            String flow,
            List<String> scopes,
            String redirectUrl,
            boolean forceAuthentication)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ProviderName", requireText(providerName, "providerName"));
        body.put("IdentityToken", requireText(identityToken, "identityToken"));
        if (flow != null && !flow.isBlank()) body.put("Flow", flow);
        if (scopes != null && !scopes.isEmpty()) body.put("Scopes", List.copyOf(scopes));
        if (redirectUrl != null && !redirectUrl.isBlank()) body.put("RedirectUrl", redirectUrl);
        if (forceAuthentication) body.put("ForceAuthentication", true);
        JsonNode response =
                post("/GetResourceOauth2Token/" + VERSION + "/id/post/application_json/", body);
        JsonNode token = response.get("AccessToken");
        if (token != null && token.isTextual() && !token.textValue().isBlank()) {
            return new OAuth2Credential(OAuth2Credential.Type.TOKEN, token.textValue());
        }
        JsonNode url = response.get("AuthorizationUrl");
        if (url != null && url.isTextual() && !url.textValue().isBlank()) {
            return new OAuth2Credential(OAuth2Credential.Type.AUTHORIZATION_URL, url.textValue());
        }
        throw new IllegalStateException(
                "Identity response contains neither AccessToken nor AuthorizationUrl.");
    }

    private JsonNode post(String path, Map<String, Object> value) throws Exception {
        byte[] body = mapper.writeValueAsBytes(value);
        URI uri = endpoint.resolve(path);
        Map<String, String> headers =
                VolcengineV4Signer.sign(
                        uri, "", body, region, "id", accessKey, secretKey, sessionToken, clock);
        HttpRequest.Builder request =
                HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        HttpResponse<byte[]> response =
                httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Identity request failed with HTTP " + response.statusCode() + ".");
        }
        return mapper.readTree(response.body());
    }

    private static Map<String, String> principal(PermissionRequest.Principal value) {
        return Map.of("Type", value.type(), "Id", value.id());
    }

    private static String requiredResponseText(JsonNode response, String field) {
        JsonNode value = response.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank())
            throw new IllegalStateException("Identity response is missing " + field + ".");
        return value.textValue();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " must be set.");
        return value.trim();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String envRequired(String name) {
        return requireText(System.getenv(name), name);
    }

    private static String envFirstRequired(String first, String second) {
        String value = System.getenv(first);
        return value == null || value.isBlank() ? envRequired(second) : value.trim();
    }
}
