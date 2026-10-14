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
package com.volcengine.veadk.a2a.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.volcengine.veadk.utils.JSONUtil;
import io.a2a.spec.AgentCard;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Client for registering and discovering Agent Cards through {@link A2AHubServer}. */
public final class A2AHubClient {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final URI serverUri;
    private final HttpClient httpClient;
    private final Duration timeout;

    public A2AHubClient(String serverHost, int serverPort) {
        this(URI.create("http://" + requireText(serverHost, "serverHost") + ":" + serverPort));
    }

    public A2AHubClient(URI serverUri) {
        this(
                serverUri,
                HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
                DEFAULT_TIMEOUT);
    }

    public A2AHubClient(URI serverUri, HttpClient httpClient, Duration timeout) {
        this.serverUri = normalize(Objects.requireNonNull(serverUri, "serverUri must be set."));
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must be set.");
        this.timeout = Objects.requireNonNull(timeout, "timeout must be set.");
        healthCheck();
    }

    public void healthCheck() {
        JsonNode response = get("/ping");
        if (!"pong!".equals(response.path("msg").asText())) {
            throw new IllegalStateException("unexpected response from A2A Hub server");
        }
    }

    public void createGroup(String groupId) {
        post("/create_group?group_id=" + encode(requireText(groupId, "groupId")), "");
    }

    public void registerAgent(String groupId, String agentId, AgentCard agentCard) {
        Objects.requireNonNull(agentCard, "agentCard must be set.");
        String body =
                JSONUtil.toJson(
                        java.util.Map.of(
                                "group_id", requireText(groupId, "groupId"),
                                "agent_id", requireText(agentId, "agentId"),
                                "agent_card", agentCard));
        ensureSuccess(post("/register_agent", body));
    }

    public List<AgentInformation> getAgentCards(String groupId) {
        return getAgentCards(groupId, List.of());
    }

    public List<AgentInformation> getAgentCards(String groupId, List<String> targetAgents) {
        JsonNode response = get("/group/" + encode(requireText(groupId, "groupId")) + "/agents");
        ensureSuccess(response);
        List<String> targets = targetAgents == null ? List.of() : List.copyOf(targetAgents);
        List<AgentInformation> result = new ArrayList<>();
        for (JsonNode info : response.path("agent_infos")) {
            String agentId = info.path("agent_id").asText();
            if (targets.isEmpty() || targets.contains(agentId)) {
                result.add(new AgentInformation(agentId, info.path("agent_card")));
            }
        }
        return List.copyOf(result);
    }

    private JsonNode get(String path) {
        HttpRequest request = HttpRequest.newBuilder(resolve(path)).timeout(timeout).GET().build();
        return send(request);
    }

    private JsonNode post(String path, String body) {
        HttpRequest request =
                HttpRequest.newBuilder(resolve(path))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return send(request);
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "unexpected status code from A2A Hub server: " + response.statusCode());
            }
            return JSONUtil.parseJson(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("A2A Hub request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("A2A Hub request was interrupted", e);
        }
    }

    private URI resolve(String path) {
        return URI.create(serverUri + path);
    }

    private static void ensureSuccess(JsonNode response) {
        if (response.path("err_code").asInt() != 0) {
            throw new IllegalStateException(response.path("msg").asText("A2A Hub request failed"));
        }
    }

    private static URI normalize(URI uri) {
        String value = uri.toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return URI.create(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be set.");
        }
        return value;
    }

    public record AgentInformation(String agentId, JsonNode agentCard) {
        public AgentInformation {
            requireText(agentId, "agentId");
            Objects.requireNonNull(agentCard, "agentCard must be set.");
        }
    }
}
