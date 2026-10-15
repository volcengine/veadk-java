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

import com.fasterxml.jackson.databind.JsonNode;
import com.volcengine.veadk.integration.agentkit.AgentKitWrapper.AgentKitSession;
import com.volcengine.veadk.utils.JSONUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** A reusable Code Sandbox session with workspace-scoped file operations. */
public final class SandboxSession implements AutoCloseable {

    public static final int DEFAULT_OPERATION_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_MAX_FILE_BYTES = 10 * 1024 * 1024;
    public static final int MAX_LISTED_FILES = 500;
    private static final int MAX_PATH_CHARS = 1024;
    private static final String WORKSPACE_GUARD =
            "root=pathlib.Path('/workspace').resolve();"
                    + "target=(root/rel).resolve();"
                    + "(_ for _ in ()).throw(ValueError('path escapes /workspace'))"
                    + " if target == root or root not in target.parents else None;";

    private final SandboxClient client;
    private final AgentKitSession session;
    private final String logicalSessionId;
    private final int ttlSeconds;
    private final AtomicBoolean closed = new AtomicBoolean();

    SandboxSession(
            SandboxClient client,
            AgentKitSession session,
            String logicalSessionId,
            int ttlSeconds) {
        this.client = Objects.requireNonNull(client, "client must be set.");
        this.session = Objects.requireNonNull(session, "session must be set.");
        this.logicalSessionId = requireText(logicalSessionId, "logicalSessionId must be set.");
        this.ttlSeconds = ttlSeconds;
    }

    public String id() {
        return session.sessionId()
                .orElseThrow(
                        () -> new IllegalStateException("AgentKit session is missing SessionId"));
    }

    public String logicalSessionId() {
        return logicalSessionId;
    }

    public Optional<String> status() {
        return session.status();
    }

    public Optional<String> endpoint() {
        return session.endpoint();
    }

    public Optional<String> createdAt() {
        return session.createdAt();
    }

    public Optional<String> expireAt() {
        return session.expireAt();
    }

    public String runCode(String code, String language, int timeoutSeconds) {
        ensureOpen();
        if (timeoutSeconds < 1 || timeoutSeconds > ttlSeconds) {
            throw new IllegalArgumentException(
                    "timeoutSeconds must be between 1 and the session TTL");
        }
        return client.runCode(
                logicalSessionId,
                requireText(code, "code must be set."),
                requireText(language, "language must be set."),
                timeoutSeconds,
                ttlSeconds);
    }

    public SandboxFile writeFile(String path, String content) {
        return writeFile(
                path,
                Objects.requireNonNull(content, "content must be set.")
                        .getBytes(StandardCharsets.UTF_8));
    }

    public SandboxFile writeFile(String path, byte[] content) {
        ensureOpen();
        String normalized = normalizeRelativePath(path);
        Objects.requireNonNull(content, "content must be set.");
        if (content.length > DEFAULT_MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "content exceeds " + DEFAULT_MAX_FILE_BYTES + " bytes");
        }
        String code =
                prelude(normalized)
                        + WORKSPACE_GUARD
                        + "target.parent.mkdir(parents=True,exist_ok=True);"
                        + "data=base64.b64decode('"
                        + Base64.getEncoder().encodeToString(content)
                        + "');target.write_bytes(data);"
                        + "print(json.dumps({'path':rel,'size':len(data)}))";
        return parseFile(runCode(code, "python3", DEFAULT_OPERATION_TIMEOUT_SECONDS));
    }

    public byte[] readFile(String path) {
        return readFile(path, DEFAULT_MAX_FILE_BYTES);
    }

    public byte[] readFile(String path, int maxBytes) {
        ensureOpen();
        String normalized = normalizeRelativePath(path);
        if (maxBytes < 1 || maxBytes > DEFAULT_MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "maxBytes must be between 1 and " + DEFAULT_MAX_FILE_BYTES);
        }
        String code =
                prelude(normalized)
                        + WORKSPACE_GUARD
                        + "(_ for _ in ()).throw(FileNotFoundError(rel))"
                        + " if not target.is_file() else None;"
                        + "data=target.read_bytes();"
                        + "(_ for _ in ()).throw(ValueError('file exceeds maxBytes'))"
                        + " if len(data)>"
                        + maxBytes
                        + " else None;"
                        + "print(base64.b64encode(data).decode())";
        return Base64.getDecoder()
                .decode(runCode(code, "python3", DEFAULT_OPERATION_TIMEOUT_SECONDS).strip());
    }

    public List<SandboxFile> listFiles() {
        return listFiles("");
    }

    public List<SandboxFile> listFiles(String directory) {
        ensureOpen();
        String normalized = normalizeDirectory(directory);
        String code =
                prelude(normalized)
                        + "root=pathlib.Path('/workspace').resolve();"
                        + "target=(root/rel).resolve();"
                        + "(_ for _ in ()).throw(ValueError('path escapes /workspace'))"
                        + " if target != root and root not in target.parents else None;"
                        + "(_ for _ in ()).throw(NotADirectoryError(rel))"
                        + " if not target.is_dir() else None;"
                        + "items=[{'path':str(p.relative_to(root)),'size':p.stat().st_size}"
                        + " for p in sorted(target.rglob('*'))"
                        + " if p.is_file() and not p.is_symlink()][:"
                        + MAX_LISTED_FILES
                        + "];"
                        + "print(json.dumps(items))";
        JsonNode result =
                parseJson(runCode(code, "python3", DEFAULT_OPERATION_TIMEOUT_SECONDS).strip());
        if (!result.isArray()) {
            throw new IllegalStateException("Sandbox listFiles returned invalid JSON");
        }
        List<SandboxFile> files = new ArrayList<>();
        result.forEach(
                value ->
                        files.add(
                                new SandboxFile(
                                        value.path("path").asText(), value.path("size").asLong())));
        return List.copyOf(files);
    }

    public boolean deleteFile(String path) {
        ensureOpen();
        String normalized = normalizeRelativePath(path);
        String code =
                prelude(normalized)
                        + WORKSPACE_GUARD
                        + "existed=target.is_file();target.unlink(missing_ok=True);"
                        + "print(json.dumps({'deleted':existed}))";
        JsonNode result =
                parseJson(runCode(code, "python3", DEFAULT_OPERATION_TIMEOUT_SECONDS).strip());
        return result.path("deleted").asBoolean(false);
    }

    @Override
    public synchronized void close() {
        if (closed.get()) {
            return;
        }
        client.deleteSession(id());
        closed.set(true);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("SandboxSession is closed");
        }
    }

    private static String prelude(String normalizedPath) {
        String encoded =
                Base64.getEncoder().encodeToString(normalizedPath.getBytes(StandardCharsets.UTF_8));
        return "import base64,json,pathlib;rel=base64.b64decode('" + encoded + "').decode();";
    }

    private static SandboxFile parseFile(String output) {
        JsonNode result = parseJson(output.strip());
        String path = result.path("path").asText();
        if (path.isBlank() || !result.path("size").canConvertToLong()) {
            throw new IllegalStateException("Sandbox file operation returned invalid JSON");
        }
        return new SandboxFile(path, result.path("size").asLong());
    }

    private static JsonNode parseJson(String value) {
        try {
            return JSONUtil.parseJson(value);
        } catch (IOException e) {
            throw new IllegalStateException("Sandbox file operation returned invalid JSON", e);
        }
    }

    private static String normalizeRelativePath(String path) {
        String normalized = normalizeDirectory(path);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("path must identify a file");
        }
        return normalized;
    }

    private static String normalizeDirectory(String path) {
        Objects.requireNonNull(path, "path must be set.");
        if (path.length() > MAX_PATH_CHARS || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("path is invalid");
        }
        Path candidate = Path.of(path.isBlank() ? "." : path);
        if (candidate.isAbsolute()) {
            throw new IllegalArgumentException("path must be relative to /workspace");
        }
        Path normalized = candidate.normalize();
        if (normalized.startsWith("..")) {
            throw new IllegalArgumentException("path must not escape /workspace");
        }
        String value = normalized.toString().replace('\\', '/');
        return ".".equals(value) ? "" : value;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
