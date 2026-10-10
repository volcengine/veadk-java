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
package com.volcengine.veadk.tools.builtin.sandbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.volcengine.veadk.integration.agentkit.AgentKitWrapper;
import com.volcengine.veadk.skills.SkillSpacePolicy;
import com.volcengine.veadk.utils.EnvUtil;
import com.volcengine.veadk.utils.JSONUtil;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class SkillsSandboxA2aClient implements AutoCloseable {

    static final int DEFAULT_TIMEOUT_SECONDS = 1800;

    private static final int HISTORY_LENGTH = 20;
    private static final long POLL_INTERVAL_MILLIS = 2000;
    private static final long MAX_POLL_INTERVAL_MILLIS = 16000;
    private static final Set<String> TERMINAL_STATES =
            Set.of(
                    "completed",
                    "failed",
                    "canceled",
                    "rejected",
                    "input-required",
                    "auth-required");
    private static final Set<Integer> RETRY_STATUS_CODES = Set.of(502, 503, 504);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AgentKitWrapper agentKitWrapper;
    private final HttpClient httpClient;

    SkillsSandboxA2aClient(AgentKitWrapper agentKitWrapper, HttpClient httpClient) {
        this.agentKitWrapper =
                Objects.requireNonNull(agentKitWrapper, "agentKitWrapper must be set.");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must be set.");
    }

    JsonNode invoke(String workflowPrompt, ToolContext context, int timeout)
            throws IOException, InterruptedException {
        String endpoint = endpoint(context, timeout);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        return sendMessage(
                requireText(workflowPrompt, "workflow_prompt"), endpoint, context, deadline);
    }

    JsonNode poll(String taskId, ToolContext context, int timeout)
            throws IOException, InterruptedException {
        String endpoint = endpoint(context, timeout);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        return getTask(requireText(taskId, "task_id"), endpoint, deadline);
    }

    String execute(String workflowPrompt, ToolContext context, int timeout)
            throws IOException, InterruptedException {
        String endpoint = endpoint(context, timeout);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        JsonNode task =
                sendMessage(
                        requireText(workflowPrompt, "workflow_prompt"),
                        endpoint,
                        context,
                        deadline);
        String taskId =
                text(task.path("id"))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "A2A message/send response task does not contain"
                                                        + " id"));
        long pollInterval = POLL_INTERVAL_MILLIS;

        while (!TERMINAL_STATES.contains(taskState(task).orElse(""))) {
            long remainingMillis = remainingMillis(deadline);
            if (remainingMillis <= 0) {
                throw new IllegalStateException("Timed out while waiting for A2A task " + taskId);
            }
            Thread.sleep(Math.min(pollInterval, remainingMillis));
            task = getTask(taskId, endpoint, deadline);
            pollInterval = Math.min(pollInterval * 2, MAX_POLL_INTERVAL_MILLIS);
        }

        String state = taskState(task).orElse("");
        if (!"completed".equals(state)) {
            throw new IllegalStateException(
                    "A2A task " + taskId + " ended with state " + state + ": " + task);
        }

        return taskResultText(task).filter(text -> !text.isBlank()).orElseGet(task::toString);
    }

    Map<String, Object> toMap(JsonNode node) {
        return JSONUtil.convertValue(node, MAP_TYPE);
    }

    static int timeoutSeconds(Object timeout) {
        if (timeout == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        int parsedTimeout;
        if (timeout instanceof Number number) {
            parsedTimeout = number.intValue();
        } else if (timeout instanceof String text) {
            parsedTimeout = Integer.parseInt(text);
        } else {
            throw new IllegalArgumentException("timeout must be an integer");
        }
        if (parsedTimeout < 1 || parsedTimeout > DEFAULT_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(
                    "timeout must be an integer between 1 and "
                            + DEFAULT_TIMEOUT_SECONDS
                            + " seconds");
        }
        return parsedTimeout;
    }

    @Override
    public void close() {
        agentKitWrapper.close();
    }

    private String endpoint(ToolContext context, int timeout) {
        String toolId = EnvUtil.getAgentKitSkillsToolId();
        String toolUserSessionId = toolUserSessionId(context);
        Optional<String> policyJson =
                SkillSpacePolicy.fromRaw(EnvUtil.getSkillSpacePolicy())
                        .map(SkillSpacePolicy::toJson);
        Map<String, String> envs =
                policyJson.map(json -> Map.of(SkillSpacePolicy.ENV_NAME, json)).orElseGet(Map::of);

        return agentKitWrapper
                .ensureSessionEndpoint(
                        toolId, toolUserSessionId, Math.max(timeout, 1800), true, envs)
                .endpoint()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "AgentKit session endpoint is not available"));
    }

    private JsonNode sendMessage(
            String workflowPrompt, String endpoint, ToolContext context, long deadline)
            throws IOException, InterruptedException {
        Map<String, Object> payload =
                ImmutableMap.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        uuidHex(),
                        "method",
                        "message/send",
                        "params",
                        ImmutableMap.of(
                                "message",
                                ImmutableMap.of(
                                        "kind",
                                        "message",
                                        "messageId",
                                        uuidHex(),
                                        "role",
                                        "user",
                                        "parts",
                                        List.of(
                                                ImmutableMap.of(
                                                        "kind", "text", "text", workflowPrompt))),
                                "metadata",
                                ImmutableMap.of(
                                        "user_id", context.userId(),
                                        "session_id", context.sessionId()),
                                "configuration",
                                ImmutableMap.of(
                                        "blocking", false, "historyLength", HISTORY_LENGTH)));
        return resultTask("A2ASendMessage", postA2a(endpoint, payload, deadline));
    }

    private JsonNode getTask(String taskId, String endpoint, long deadline)
            throws IOException, InterruptedException {
        Map<String, Object> payload =
                ImmutableMap.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        uuidHex(),
                        "method",
                        "tasks/get",
                        "params",
                        ImmutableMap.of("id", taskId, "historyLength", HISTORY_LENGTH));
        return resultTask("A2AGetTask", postA2a(endpoint, payload, deadline));
    }

    private JsonNode postA2a(String endpoint, Map<String, Object> payload, long deadline)
            throws IOException, InterruptedException {
        URI uri = a2aUri(endpoint);
        while (true) {
            HttpRequest request =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(requestTimeoutSeconds(deadline)))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSONUtil.toJson(payload)))
                            .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return JSONUtil.parseJson(response.body());
            }
            if (RETRY_STATUS_CODES.contains(response.statusCode())
                    && remainingMillis(deadline) > 0) {
                Thread.sleep(Math.min(POLL_INTERVAL_MILLIS, remainingMillis(deadline)));
                continue;
            }
            throw new IllegalStateException(
                    "AgentKit Skill /a2a request failed with HTTP "
                            + response.statusCode()
                            + ": "
                            + response.body());
        }
    }

    private static JsonNode resultTask(String operation, JsonNode response) {
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new IllegalStateException(error.toString());
        }
        JsonNode result = response.path("result");
        if (!result.isObject()) {
            throw new IllegalStateException(operation + " response does not contain result task");
        }
        if (!"task".equals(result.path("kind").asText()) && result.path("status").isMissingNode()) {
            throw new IllegalStateException(operation + " response result is not an A2A task");
        }
        return result;
    }

    private static Optional<String> taskState(JsonNode task) {
        return text(task.path("status").path("state"));
    }

    private static Optional<String> taskResultText(JsonNode task) {
        String artifactText = textFromArtifacts(task.path("artifacts"));
        if (!artifactText.isBlank()) {
            return Optional.of(artifactText);
        }

        String statusText = textFromParts(task.path("status").path("message").path("parts"));
        if (!statusText.isBlank()) {
            return Optional.of(statusText);
        }

        JsonNode history = task.path("history");
        if (history.isArray()) {
            List<JsonNode> messages = new ArrayList<>();
            history.forEach(messages::add);
            for (int index = messages.size() - 1; index >= 0; index--) {
                JsonNode message = messages.get(index);
                String role = message.path("role").asText();
                if ("agent".equals(role) || "assistant".equals(role)) {
                    String text = textFromParts(message.path("parts"));
                    if (!text.isBlank()) {
                        return Optional.of(text);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static String textFromArtifacts(JsonNode artifacts) {
        if (!artifacts.isArray()) {
            return "";
        }
        List<String> chunks = new ArrayList<>();
        for (JsonNode artifact : artifacts) {
            String text = textFromParts(artifact.path("parts"));
            if (!text.isBlank()) {
                chunks.add(text);
            }
        }
        return String.join("\n", chunks);
    }

    private static String textFromParts(JsonNode parts) {
        if (!parts.isArray()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            if (part.path("metadata").path("adk_thought").asBoolean(false)) {
                continue;
            }
            if (part.path("text").isTextual()) {
                text.append(part.path("text").asText());
            } else if (part.path("textPart").path("text").isTextual()) {
                text.append(part.path("textPart").path("text").asText());
            }
        }
        return text.toString();
    }

    private static URI a2aUri(String endpoint) {
        try {
            URI uri = new URI(endpoint);
            String path = uri.getPath() == null ? "" : uri.getPath().replaceFirst("/+$", "");
            String a2aPath = path.endsWith("/a2a") ? path : path + "/a2a";
            if (a2aPath.isBlank()) {
                a2aPath = "/a2a";
            }
            return new URI(
                    uri.getScheme(),
                    uri.getAuthority(),
                    a2aPath,
                    uri.getQuery(),
                    uri.getFragment());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "AgentKit session endpoint is invalid: " + endpoint, e);
        }
    }

    private static String toolUserSessionId(ToolContext context) {
        return context.agentName() + "_" + context.userId() + "_" + context.sessionId();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be set.");
        }
        return value;
    }

    private static Optional<String> text(JsonNode node) {
        if (!node.isMissingNode()
                && !node.isNull()
                && node.isTextual()
                && !node.asText().isBlank()) {
            return Optional.of(node.asText());
        }
        return Optional.empty();
    }

    private static int requestTimeoutSeconds(long deadline) {
        return Math.max(
                1,
                (int) Math.min(60, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime())));
    }

    private static long remainingMillis(long deadline) {
        return TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    }

    private static String uuidHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
