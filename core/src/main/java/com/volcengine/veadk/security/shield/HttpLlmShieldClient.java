/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.shield;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volcengine.veadk.security.VolcengineV4Signer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** HTTP client for the LLM Shield Moderate 2025-08-31 API. */
public final class HttpLlmShieldClient implements LlmShieldClient {
    private static final String QUERY = "Action=Moderate&Version=2025-08-31";
    private static final Map<Integer, String> CATEGORIES =
            Map.of(
                    101,
                    "Model Misuse",
                    103,
                    "Sensitive Information",
                    104,
                    "Prompt Injection",
                    106,
                    "General Topic Control",
                    107,
                    "Computational Resource Consumption");
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String appId;
    private final String region;
    private final String apiKey;
    private final String accessKey;
    private final String secretKey;
    private final String sessionToken;
    private final Duration timeout;
    private final Clock clock;

    public static HttpLlmShieldClient fromEnvironment() {
        String region = env("TOOL_LLM_SHIELD_REGION", env("REGION", "cn-beijing"));
        return new HttpLlmShieldClient(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                URI.create(
                        env(
                                "TOOL_LLM_SHIELD_URL",
                                "https://" + region + ".sdk.access.llm-shield.omini-shield.com")),
                env("TOOL_LLM_SHIELD_APP_ID", ""),
                region,
                env("TOOL_LLM_SHIELD_API_KEY", ""),
                env("VOLCENGINE_ACCESS_KEY", ""),
                env("VOLCENGINE_SECRET_KEY", ""),
                env("VOLCENGINE_SESSION_TOKEN", ""),
                Duration.ofSeconds(50),
                Clock.systemUTC());
    }

    HttpLlmShieldClient(
            HttpClient httpClient,
            ObjectMapper mapper,
            URI endpoint,
            String appId,
            String region,
            String apiKey,
            String accessKey,
            String secretKey,
            String sessionToken,
            Duration timeout,
            Clock clock) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.appId = blankToEmpty(appId);
        this.region = requireText(region, "region");
        this.apiKey = blankToEmpty(apiKey);
        this.accessKey = blankToEmpty(accessKey);
        this.secretKey = blankToEmpty(secretKey);
        this.sessionToken = blankToEmpty(sessionToken);
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public Optional<String> moderate(
            String message, String role, String hookName, String sessionId, String invocationId)
            throws Exception {
        if (appId.isEmpty()) {
            throw new IllegalStateException("LLM Shield app ID is not configured.");
        }
        if (apiKey.isEmpty() && (accessKey.isEmpty() || secretKey.isEmpty())) {
            throw new IllegalStateException("LLM Shield credentials are not configured.");
        }
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("Message", Map.of("Role", role, "Content", message, "ContentType", 1));
        requestBody.put("Scene", appId);
        byte[] body = mapper.writeValueAsBytes(requestBody);
        URI uri = endpoint.resolve("/v2/moderate?" + QUERY);
        HttpRequest.Builder request =
                HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (!apiKey.isEmpty()) {
            request.header("x-api-key", apiKey);
        } else {
            VolcengineV4Signer.sign(
                            uri,
                            QUERY,
                            body,
                            region,
                            "llmshield",
                            accessKey,
                            secretKey,
                            sessionToken,
                            clock)
                    .forEach(request::header);
            request.header("X-Top-Service", "llmshield").header("X-Top-Region", region);
        }
        HttpResponse<byte[]> response =
                httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "LLM Shield request failed with HTTP " + response.statusCode() + ".");
        }
        return decision(mapper.readTree(response.body()));
    }

    private static Optional<String> decision(JsonNode response) {
        JsonNode result = response.path("Result");
        if (result.path("Decision").path("DecisionType").asInt() != 2) return Optional.empty();
        Set<String> categories = new LinkedHashSet<>();
        for (JsonNode risk : result.path("RiskInfo").path("Risks")) {
            int category = risk.path("Category").asInt();
            if (category != 0)
                categories.add(CATEGORIES.getOrDefault(category, "Category " + category));
        }
        String reasons =
                categories.isEmpty() ? "security policy violation" : String.join(", ", categories);
        return Optional.of(
                "Your request has been blocked due to: "
                        + reasons
                        + ". Please modify your input and try again.");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " must be set.");
        return value.trim();
    }
}
