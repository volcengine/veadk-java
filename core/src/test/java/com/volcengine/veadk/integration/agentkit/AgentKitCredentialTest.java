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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentKitCredentialTest {

    @TempDir Path tempDir;

    @Test
    void loadCredentialFile() throws Exception {
        Path credentialFile = tempDir.resolve("credential");
        Files.writeString(
                credentialFile,
                """
                {
                  "access_key_id": "test-ak",
                  "secret_access_key": "test-sk",
                  "session_token": "test-token"
                }
                """);

        AgentKitCredential credential = AgentKitCredential.load(credentialFile);

        assertThat(credential.accessKey()).isEqualTo("test-ak");
        assertThat(credential.secretKey()).isEqualTo("test-sk");
        assertThat(credential.sessionToken()).isEqualTo("test-token");
    }
}
