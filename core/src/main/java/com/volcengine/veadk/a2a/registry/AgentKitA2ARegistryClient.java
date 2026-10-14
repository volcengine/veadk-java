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
package com.volcengine.veadk.a2a.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.volcengine.veadk.integration.agentkit.AgentKitWrapper;
import com.volcengine.veadk.utils.EnvUtil;
import com.volcengine.veadk.utils.JSONUtil;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Searches remote A2A Agent Cards without returning endpoints or credentials to the model. */
public final class AgentKitA2ARegistryClient implements AutoCloseable {

    public static final int MAX_PROMPT_BYTES = 2048;

    private final AgentKitA2ARegistryConfig config;
    private final AgentKitWrapper wrapper;
    private final boolean ownsWrapper;

    public AgentKitA2ARegistryClient() {
        this(AgentKitA2ARegistryConfig.fromEnv());
    }

    public AgentKitA2ARegistryClient(AgentKitA2ARegistryConfig config) {
        this.config = Objects.requireNonNull(config, "config must be set.");
        if (config.spaceId().isBlank()) {
            throw new IllegalArgumentException("Missing required registry config: space_id");
        }
        this.wrapper =
                new AgentKitWrapper(
                        config.endpoint(),
                        config.region(),
                        EnvUtil.getAccessKey(),
                        EnvUtil.getSecretKey());
        String sessionToken = EnvUtil.getSessionToken();
        if (sessionToken != null && !sessionToken.isBlank()) {
            wrapper.setSessionToken(sessionToken);
        }
        this.ownsWrapper = true;
    }

    public AgentKitA2ARegistryClient(AgentKitA2ARegistryConfig config, AgentKitWrapper wrapper) {
        this.config = Objects.requireNonNull(config, "config must be set.");
        this.wrapper = Objects.requireNonNull(wrapper, "wrapper must be set.");
        this.ownsWrapper = false;
    }

    public Map<String, Object> searchAgentCards(String prompt) {
        return searchAgentCards(prompt, config.topK());
    }

    public Map<String, Object> searchAgentCards(String prompt, int topK) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        if (config.spaceId().isBlank()) {
            throw new IllegalArgumentException("Missing required registry config: space_id");
        }
        String requestPrompt = truncateUtf8(prompt.strip(), MAX_PROMPT_BYTES);
        JsonNode result =
                wrapper.searchAgentCards(
                        config.spaceId(), requestPrompt, Math.max(1, Math.min(topK, 20)));
        List<Map<String, Object>> agents = new ArrayList<>();
        JsonNode rawCards = result.path("AgentCards");
        if (rawCards.isArray()) {
            for (JsonNode rawCard : rawCards) {
                JsonNode card = parseCard(rawCard);
                agents.add(sanitize(card));
            }
        }
        if (agents.isEmpty()) {
            throw new IllegalStateException("SearchAgentCards did not return usable agents");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("outcome", "success");
        response.put("agents", agents);
        response.put("total_count", result.path("TotalCount").asInt(agents.size()));
        response.put("diagnostics", Map.of());
        return response;
    }

    @Override
    public void close() {
        if (ownsWrapper) {
            wrapper.close();
        }
    }

    static String truncateUtf8(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        int length = maxBytes;
        while (length > 0 && (bytes[length] & 0xC0) == 0x80) {
            length--;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    private static JsonNode parseCard(JsonNode rawCard) {
        if (rawCard.isObject()) {
            return rawCard;
        }
        if (!rawCard.isTextual()) {
            throw new IllegalStateException("Agent Card is not a JSON object or string");
        }
        try {
            return JSONUtil.parseJson(rawCard.asText());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Agent Card", e);
        }
    }

    private static Map<String, Object> sanitize(JsonNode card) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", card.path("name").asText(""));
        value.put("description", card.path("description").asText(""));
        value.put(
                "version",
                card.path("version").asText(card.path("latestPublishedVersion").asText("")));
        value.put("protocol_version", card.path("protocolVersion").asText(""));
        value.put("preferred_transport", card.path("preferredTransport").asText(""));
        value.put("registration_type", card.path("registrationType").asText(""));
        List<Map<String, Object>> skills = new ArrayList<>();
        for (JsonNode skill : card.path("skills")) {
            skills.add(
                    Map.of(
                            "id", skill.path("id").asText(""),
                            "name", skill.path("name").asText(""),
                            "description", skill.path("description").asText(""),
                            "tags",
                                    JSONUtil.convertValue(
                                            skill.path("tags"),
                                            new TypeReference<List<String>>() {})));
        }
        value.put("skills", skills);
        return value;
    }
}
