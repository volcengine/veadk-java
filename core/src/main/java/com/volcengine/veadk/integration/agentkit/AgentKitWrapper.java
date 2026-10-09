package com.volcengine.veadk.integration.agentkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.volcengine.error.SdkError;
import com.volcengine.helper.Const;
import com.volcengine.model.ApiInfo;
import com.volcengine.model.Credentials;
import com.volcengine.model.ServiceInfo;
import com.volcengine.model.response.RawResponse;
import com.volcengine.service.BaseServiceImpl;
import com.volcengine.veadk.utils.JSONUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.http.Header;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A wrapper service implementation to invoke Volcengine AgentKit tools.
 */
public class AgentKitWrapper extends BaseServiceImpl implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentKitWrapper.class);

    private static final String ACTION_INVOKE_TOOL = "InvokeTool";
    private static final String ACTION_LIST_SESSIONS = "ListSessions";
    private static final String ACTION_CREATE_SESSION = "CreateSession";
    private static final String ACTION_GET_SESSION = "GetSession";
    private static final String ACTION_LIST_SKILLS_BY_SPACE_ID = "ListSkillsBySpaceId";
    private static final String ACTION_GEN_TEMP_TOS_OBJECT_DOWNLOAD_URL =
            "GenTempTosObjectDownloadUrl";
    private static final String API_VERSION = "2025-10-30";
    private static final int SESSION_PAGE_SIZE = 20;
    private static final long SESSION_READY_TIMEOUT_MILLIS = 120_000;
    private static final long SESSION_POLL_INTERVAL_MILLIS = 1_000;

    private static final ServiceInfo SERVICE_INFO =
            new ServiceInfo(
                    new HashMap<String, Object>() {
                        {
                            put(Const.CONNECTION_TIMEOUT, 5000);
                            put(Const.SOCKET_TIMEOUT, 30000); // Sandbox might be slow
                            put(Const.Scheme, "https");
                            put(
                                    Const.Header,
                                    new ArrayList<Header>() {
                                        {
                                            add(new BasicHeader("Accept", "application/json"));
                                        }
                                    });
                            put(Const.Credentials, new Credentials("cn-beijing", "agentkit"));
                        }
                    });

    private static final Map<String, ApiInfo> API_INFO_LIST =
            new HashMap<String, ApiInfo>() {
                {
                    put(ACTION_INVOKE_TOOL, jsonPostApiInfo());
                    put(ACTION_LIST_SESSIONS, jsonPostApiInfo());
                    put(ACTION_CREATE_SESSION, jsonPostApiInfo());
                    put(ACTION_GET_SESSION, jsonPostApiInfo());
                    put(ACTION_LIST_SKILLS_BY_SPACE_ID, jsonPostApiInfo());
                    put(ACTION_GEN_TEMP_TOS_OBJECT_DOWNLOAD_URL, jsonPostApiInfo());
                }
            };

    private static ApiInfo jsonPostApiInfo() {
        return new ApiInfo(
                new HashMap<String, Object>() {
                    {
                        put(Const.Method, "POST");
                        put(Const.Path, "/");
                        put(
                                Const.Header,
                                Arrays.asList(
                                        new BasicHeader("Accept", "application/json"),
                                        new BasicHeader("Content-Type", "application/json")));
                    }
                });
    }

    public AgentKitWrapper(String host, String region, String ak, String sk) {
        super(SERVICE_INFO, API_INFO_LIST);
        this.setAccessKey(ak);
        this.setSecretKey(sk);
        this.setHost(host);
        this.getServiceInfo().setHost(host);
        this.setRegion(region);
        this.getServiceInfo().getCredentials().setRegion(region);
    }

    @Override
    public void close() {
        destroy();
    }

    public AgentKitSession ensureSessionEndpoint(
            String toolId, String toolUserSessionId, int ttl, boolean waitUntilReady) {
        return ensureSessionEndpoint(toolId, toolUserSessionId, ttl, waitUntilReady, Map.of());
    }

    public AgentKitSession ensureSessionEndpoint(
            String toolId,
            String toolUserSessionId,
            int ttl,
            boolean waitUntilReady,
            Map<String, String> envs) {
        AgentKitSession session = findReusableSession(toolId, toolUserSessionId).orElse(null);
        if (session == null) {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("ToolId", requireText(toolId, "toolId must be set."));
            requestBody.put(
                    "UserSessionId",
                    requireText(toolUserSessionId, "toolUserSessionId must be set."));
            requestBody.put("Ttl", ttl);
            if (envs != null && !envs.isEmpty()) {
                requestBody.put(
                        "Envs",
                        envs.entrySet().stream()
                                .map(
                                        entry ->
                                                Map.of(
                                                        "Key",
                                                        requireText(entry.getKey(), "env key"),
                                                        "Value",
                                                        Objects.requireNonNullElse(
                                                                entry.getValue(), "")))
                                .toList());
            }
            session = parseSession(invokeAction(ACTION_CREATE_SESSION, requestBody));
        }

        if (!waitUntilReady && session.endpoint().isPresent()) {
            return session;
        }

        String sessionId =
                session.sessionId()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "AgentKit CreateSession response is missing"
                                                        + " SessionId"));
        long deadline = System.currentTimeMillis() + SESSION_READY_TIMEOUT_MILLIS;
        while (true) {
            AgentKitSession currentSession = getSession(toolId, sessionId);
            if (!waitUntilReady || "ready".equalsIgnoreCase(currentSession.status().orElse(""))) {
                AgentKitSession resolvedSession =
                        currentSession.endpoint().isPresent()
                                ? currentSession
                                : session.withEndpointFrom(currentSession);
                if (resolvedSession.endpoint().isPresent()) {
                    return resolvedSession;
                }
                throw new IllegalStateException(
                        "AgentKit session " + sessionId + " is Ready but has no endpoint");
            }
            String status = currentSession.status().orElse("Unknown");
            if (isTerminalStatus(status)) {
                throw new IllegalStateException(
                        "AgentKit session " + sessionId + " entered terminal status " + status);
            }
            long remainingMillis = deadline - System.currentTimeMillis();
            if (remainingMillis <= 0) {
                throw new IllegalStateException(
                        "Timed out waiting for AgentKit session "
                                + sessionId
                                + " to become Ready; last status: "
                                + status);
            }
            try {
                Thread.sleep(Math.min(SESSION_POLL_INTERVAL_MILLIS, remainingMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for AgentKit session " + sessionId, e);
            }
        }
    }

    public AgentKitSession getSession(String toolId, String sessionId) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("ToolId", requireText(toolId, "toolId must be set."));
        requestBody.put("SessionId", requireText(sessionId, "sessionId must be set."));
        return parseSession(invokeAction(ACTION_GET_SESSION, requestBody));
    }

    public Optional<AgentKitSession> findReusableSession(String toolId, String toolUserSessionId) {
        Map<String, Object> filter = new HashMap<>();
        filter.put("Name", "UserSessionId");
        filter.put(
                "Values",
                List.of(requireText(toolUserSessionId, "toolUserSessionId must be set.")));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("ToolId", requireText(toolId, "toolId must be set."));
        requestBody.put("Filters", List.of(filter));
        requestBody.put("PageSize", SESSION_PAGE_SIZE);

        try {
            JsonNode result = invokeAction(ACTION_LIST_SESSIONS, requestBody);
            JsonNode sessionInfos = result.path("SessionInfos");
            if (!sessionInfos.isArray()) {
                return Optional.empty();
            }
            List<AgentKitSession> candidates = new ArrayList<>();
            for (JsonNode sessionInfo : sessionInfos) {
                AgentKitSession session = parseSession(sessionInfo);
                if (toolUserSessionId.equals(session.userSessionId().orElse(""))
                        && !isTerminalStatus(session.status().orElse(""))) {
                    candidates.add(session);
                }
            }
            return candidates.stream()
                    .max(Comparator.comparing(session -> session.createdAt().orElse("")));
        } catch (Exception e) {
            log.debug("AgentKit ListSessions failed, falling back to CreateSession", e);
            return Optional.empty();
        }
    }

    public String runCode(
            String toolId, String sessionId, String code, String language, int timeout) {

        try {
            String toolUserSessionId = "veadk_java_" + sessionId;

            Map<String, Object> payload = new HashMap<>();
            payload.put("code", code);
            payload.put("timeout", timeout);
            payload.put("kernel_name", language);

            JsonNode result = invokeTool(toolId, toolUserSessionId, "RunCode", payload, 1800);
            JsonNode resultNode = result.path("Result");

            if (!resultNode.isMissingNode()) {
                return resultNode.asText();
            }
            return result.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to run code via AgentKit", e);
        }
    }

    public JsonNode listSkillsBySpaceId(String skillSpaceId) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("SkillSpaceId", requireText(skillSpaceId, "skillSpaceId must be set."));
        requestBody.put("InnerTags", Map.of("source", "sandbox"));
        return invokeAction(ACTION_LIST_SKILLS_BY_SPACE_ID, requestBody);
    }

    public String generateTempTosObjectDownloadUrl(String skillId, String skillVersion) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("SkillId", requireText(skillId, "skillId must be set."));
        requestBody.put("SkillVersion", requireText(skillVersion, "skillVersion must be set."));
        JsonNode result = invokeAction(ACTION_GEN_TEMP_TOS_OBJECT_DOWNLOAD_URL, requestBody);
        return text(result, "SignedUrl", "signed_url")
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "AgentKit GenTempTosObjectDownloadUrl response is missing"
                                                + " SignedUrl"));
    }

    public JsonNode invokeTool(
            String toolId,
            String toolUserSessionId,
            String operationType,
            Map<String, Object> operationPayload,
            int ttl) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("ToolId", requireText(toolId, "toolId must be set."));
        requestBody.put(
                "UserSessionId", requireText(toolUserSessionId, "toolUserSessionId must be set."));
        requestBody.put("OperationType", requireText(operationType, "operationType must be set."));
        requestBody.put("OperationPayload", JSONUtil.toJson(operationPayload));
        requestBody.put("Ttl", ttl);
        return invokeAction(ACTION_INVOKE_TOOL, requestBody);
    }

    private JsonNode invokeAction(String action, Map<String, Object> requestBody) {
        String bodyStr = JSONUtil.toJson(requestBody);
        try {
            RawResponse response = json(action, actionParams(action), bodyStr);
            if (response.getCode() != SdkError.SUCCESS.getNumber()) {
                log.error(
                        "{} request:{}, raw response:{}",
                        action,
                        bodyStr,
                        response.getException() == null
                                ? ""
                                : response.getException().getMessage());
                if (response.getException() != null) {
                    throw response.getException();
                }
                throw new IllegalStateException(
                        action + " failed with SDK code " + response.getCode());
            }

            JsonNode rootNode = JSONUtil.parseJson(response.getData());
            JsonNode errorNode = rootNode.path("ResponseMetadata").path("Error");
            if (!errorNode.isMissingNode() && !errorNode.isNull()) {
                throw new IllegalStateException(action + " failed: " + errorNode.toString());
            }
            JsonNode resultNode = rootNode.path("Result");
            if (resultNode.isMissingNode()) {
                throw new IllegalStateException(action + " response does not contain Result");
            }
            log.debug("{} request:{}, raw response:{}", action, bodyStr, rootNode);
            return resultNode;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call AgentKit " + action, e);
        }
    }

    private static List<NameValuePair> actionParams(String action) {
        return Arrays.asList(
                new BasicNameValuePair("Action", action),
                new BasicNameValuePair("Version", API_VERSION));
    }

    private static AgentKitSession parseSession(JsonNode node) {
        return new AgentKitSession(
                text(node, "SessionId", "session_id"),
                text(node, "UserSessionId", "user_session_id"),
                text(node, "Status", "status"),
                text(node, "Endpoint", "endpoint"),
                text(node, "InternalEndpoint", "internal_endpoint"),
                text(node, "CreatedAt", "created_at"));
    }

    private static Optional<String> text(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return Optional.of(value.asText());
            }
        }
        return Optional.empty();
    }

    private static boolean isTerminalStatus(String status) {
        String normalizedStatus = status.strip().toLowerCase();
        return "failed".equals(normalizedStatus)
                || "terminating".equals(normalizedStatus)
                || "terminated".equals(normalizedStatus);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    public static final class AgentKitSession {
        private final Optional<String> sessionId;
        private final Optional<String> userSessionId;
        private final Optional<String> status;
        private final Optional<String> endpoint;
        private final Optional<String> internalEndpoint;
        private final Optional<String> createdAt;

        private AgentKitSession(
                Optional<String> sessionId,
                Optional<String> userSessionId,
                Optional<String> status,
                Optional<String> endpoint,
                Optional<String> internalEndpoint,
                Optional<String> createdAt) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId must be set.");
            this.userSessionId =
                    Objects.requireNonNull(userSessionId, "userSessionId must be set.");
            this.status = Objects.requireNonNull(status, "status must be set.");
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint must be set.");
            this.internalEndpoint =
                    Objects.requireNonNull(internalEndpoint, "internalEndpoint must be set.");
            this.createdAt = Objects.requireNonNull(createdAt, "createdAt must be set.");
        }

        public static AgentKitSession of(
                String sessionId,
                String userSessionId,
                String status,
                String endpoint,
                String internalEndpoint,
                String createdAt) {
            return new AgentKitSession(
                    Optional.ofNullable(sessionId),
                    Optional.ofNullable(userSessionId),
                    Optional.ofNullable(status),
                    Optional.ofNullable(endpoint),
                    Optional.ofNullable(internalEndpoint),
                    Optional.ofNullable(createdAt));
        }

        public Optional<String> sessionId() {
            return sessionId;
        }

        public Optional<String> userSessionId() {
            return userSessionId;
        }

        public Optional<String> status() {
            return status;
        }

        public Optional<String> endpoint() {
            return endpoint.or(() -> internalEndpoint);
        }

        public Optional<String> internalEndpoint() {
            return internalEndpoint;
        }

        public Optional<String> createdAt() {
            return createdAt;
        }

        private AgentKitSession withEndpointFrom(AgentKitSession other) {
            return new AgentKitSession(
                    sessionId.or(other::sessionId),
                    userSessionId.or(other::userSessionId),
                    status.or(other::status),
                    endpoint.or(other::endpoint),
                    internalEndpoint.or(other::internalEndpoint),
                    createdAt.or(other::createdAt));
        }
    }
}
