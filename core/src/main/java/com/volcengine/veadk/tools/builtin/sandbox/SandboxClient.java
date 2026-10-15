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

import com.volcengine.veadk.integration.agentkit.AgentKitWrapper;
import com.volcengine.veadk.integration.agentkit.AgentKitWrapper.AgentKitSession;
import com.volcengine.veadk.utils.EnvUtil;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Client for reusable AgentKit Code Sandbox sessions. */
public final class SandboxClient implements AutoCloseable {

    public static final int DEFAULT_TTL_SECONDS = 1800;

    private final AgentKitWrapper agentKit;
    private final String toolId;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SandboxClient() {
        this(
                new AgentKitWrapper(
                        EnvUtil.getAgentKitHost(),
                        EnvUtil.getAgentKitRegion(),
                        EnvUtil.getAccessKey(),
                        EnvUtil.getSecretKey()),
                EnvUtil.getAgentKitToolId());
    }

    public SandboxClient(AgentKitWrapper agentKit, String toolId) {
        this.agentKit = Objects.requireNonNull(agentKit, "agentKit must be set.");
        this.toolId = requireText(toolId, "toolId must be set.");
    }

    /** Creates a session or reuses a live session with the same logical session ID. */
    public SandboxSession createSession(String logicalSessionId) {
        return createSession(logicalSessionId, DEFAULT_TTL_SECONDS);
    }

    /** Creates a session or reuses a live session with the same logical session ID. */
    public SandboxSession createSession(String logicalSessionId, int ttlSeconds) {
        ensureOpen();
        validateTtl(ttlSeconds);
        String resolvedId = requireText(logicalSessionId, "logicalSessionId must be set.");
        AgentKitSession session =
                agentKit.ensureSessionEndpoint(toolId, resolvedId, ttlSeconds, true);
        return new SandboxSession(this, session, resolvedId, ttlSeconds);
    }

    /** Reads an existing physical session and binds file operations to its logical ID. */
    public SandboxSession getSession(String sessionId) {
        ensureOpen();
        AgentKitSession session =
                agentKit.getSession(toolId, requireText(sessionId, "sessionId must be set."));
        String logicalId =
                session.userSessionId()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "AgentKit session is missing UserSessionId"));
        return new SandboxSession(this, session, logicalId, DEFAULT_TTL_SECONDS);
    }

    /** Lists the current physical sessions. */
    public List<SandboxSession> listSessions() {
        ensureOpen();
        return agentKit.listSessions(toolId).stream()
                .filter(session -> session.sessionId().isPresent())
                .filter(session -> session.userSessionId().isPresent())
                .map(
                        session ->
                                new SandboxSession(
                                        this,
                                        session,
                                        session.userSessionId().orElseThrow(),
                                        DEFAULT_TTL_SECONDS))
                .toList();
    }

    void deleteSession(String sessionId) {
        ensureOpen();
        agentKit.deleteSession(toolId, sessionId);
    }

    String runCode(
            String logicalSessionId,
            String code,
            String language,
            int timeoutSeconds,
            int ttlSeconds) {
        ensureOpen();
        return agentKit.runCodeInSession(
                toolId, logicalSessionId, code, language, timeoutSeconds, ttlSeconds);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            agentKit.close();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("SandboxClient is closed");
        }
    }

    private static void validateTtl(int ttlSeconds) {
        if (ttlSeconds < 60 || ttlSeconds > 86400) {
            throw new IllegalArgumentException("ttlSeconds must be between 60 and 86400");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
