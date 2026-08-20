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
package com.volcengine.veadk.integration.agentkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.volcengine.veadk.utils.JSONUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/** Credentials used to sign AgentKit tool requests. */
public record AgentKitCredential(String accessKey, String secretKey, String sessionToken) {

    private static final String ACCESS_KEY_ENV = "VOLCENGINE_ACCESS_KEY";
    private static final String SECRET_KEY_ENV = "VOLCENGINE_SECRET_KEY";
    private static final String SESSION_TOKEN_ENV = "VOLCENGINE_SESSION_TOKEN";
    private static final List<String> CREDENTIAL_PATH_ENVS =
            List.of(
                    "FAAS_IAM_ROLE_CREDENTIAL_PATH",
                    "BYTEFAAS_IAM_ROLE_CREDENTIAL_PATH",
                    "RUNTIME_IAM_ROLE_CREDENTIAL_PATH");
    private static final List<Path> DEFAULT_CREDENTIAL_PATHS =
            List.of(
                    Path.of("/var/run/secrets/iam/credential"),
                    Path.of("/var/run/secrets/faas/iam_role_credential"),
                    Path.of("/app/.faas/iam_role_credential"));

    public static AgentKitCredential load() {
        String accessKey = System.getenv(ACCESS_KEY_ENV);
        String secretKey = System.getenv(SECRET_KEY_ENV);
        if (StringUtils.isNotBlank(accessKey) && StringUtils.isNotBlank(secretKey)) {
            return new AgentKitCredential(accessKey, secretKey, System.getenv(SESSION_TOKEN_ENV));
        }

        Path credentialPath = findCredentialPath();
        if (credentialPath == null) {
            throw new IllegalStateException(
                    "AgentKit credentials are missing. Configure VOLCENGINE_ACCESS_KEY and "
                            + "VOLCENGINE_SECRET_KEY, or mount a FaaS IAM role credential file.");
        }
        return load(credentialPath);
    }

    static AgentKitCredential load(Path credentialPath) {
        try {
            JsonNode credential = JSONUtil.parseJson(Files.readString(credentialPath));
            String accessKey = requiredText(credential, "access_key_id", credentialPath);
            String secretKey = requiredText(credential, "secret_access_key", credentialPath);
            String sessionToken = credential.path("session_token").asText(null);
            return new AgentKitCredential(accessKey, secretKey, sessionToken);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read AgentKit credential file: " + credentialPath, e);
        }
    }

    private static Path findCredentialPath() {
        for (String envName : CREDENTIAL_PATH_ENVS) {
            String configuredPath = System.getenv(envName);
            if (StringUtils.isNotBlank(configuredPath)) {
                Path path = Path.of(configuredPath);
                if (Files.isRegularFile(path)) {
                    return path;
                }
            }
        }
        return DEFAULT_CREDENTIAL_PATHS.stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
    }

    private static String requiredText(JsonNode credential, String field, Path credentialPath) {
        String value = credential.path(field).asText(null);
        if (StringUtils.isBlank(value)) {
            throw new IllegalStateException(
                    "Credential field '" + field + "' is missing in " + credentialPath);
        }
        return value;
    }
}
