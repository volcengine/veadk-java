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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.volcengine.veadk.integration.agentkit.AgentKitWrapper;
import com.volcengine.veadk.integration.agentkit.AgentKitWrapper.AgentKitSession;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SandboxClientTest {

    @Test
    void createsReusableSessionAndExposesMetadata() {
        AgentKitWrapper agentKit = mock(AgentKitWrapper.class);
        AgentKitSession metadata = session("physical-1", "logical-1");
        when(agentKit.ensureSessionEndpoint("tool-1", "logical-1", 900, true)).thenReturn(metadata);

        try (SandboxClient client = new SandboxClient(agentKit, "tool-1")) {
            SandboxSession session = client.createSession("logical-1", 900);

            assertThat(session.id()).isEqualTo("physical-1");
            assertThat(session.logicalSessionId()).isEqualTo("logical-1");
            assertThat(session.status()).contains("Ready");
            assertThat(session.expireAt()).contains("2026-10-16T00:00:00Z");
        }
        verify(agentKit).close();
    }

    @Test
    void listsGetsAndClosesPhysicalSessions() {
        AgentKitWrapper agentKit = mock(AgentKitWrapper.class);
        AgentKitSession metadata = session("physical-1", "logical-1");
        when(agentKit.listSessions("tool-1")).thenReturn(List.of(metadata));
        when(agentKit.getSession("tool-1", "physical-1")).thenReturn(metadata);

        SandboxClient client = new SandboxClient(agentKit, "tool-1");
        assertThat(client.listSessions())
                .extracting(SandboxSession::id)
                .containsExactly("physical-1");
        SandboxSession session = client.getSession("physical-1");
        session.close();

        verify(agentKit).deleteSession("tool-1", "physical-1");
        assertThatThrownBy(() -> session.runCode("print(1)", "python3", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        client.close();
    }

    @Test
    void fileApiUsesBoundedWorkspaceScriptsAndParsesResults() {
        AgentKitWrapper agentKit = mock(AgentKitWrapper.class);
        SandboxClient client = new SandboxClient(agentKit, "tool-1");
        SandboxSession session =
                new SandboxSession(client, session("physical-1", "logical-1"), "logical-1", 1800);
        when(agentKit.runCodeInSession(
                        eq("tool-1"),
                        eq("logical-1"),
                        anyString(),
                        eq("python3"),
                        anyInt(),
                        eq(1800)))
                .thenReturn("{\"path\":\"notes/a.txt\",\"size\":5}")
                .thenReturn(
                        Base64.getEncoder()
                                .encodeToString("hello".getBytes(StandardCharsets.UTF_8)))
                .thenReturn("[{\"path\":\"notes/a.txt\",\"size\":5}]")
                .thenReturn("{\"deleted\":true}");

        assertThat(session.writeFile("notes/a.txt", "hello"))
                .isEqualTo(new SandboxFile("notes/a.txt", 5));
        assertThat(session.readFile("notes/a.txt"))
                .asString(StandardCharsets.UTF_8)
                .isEqualTo("hello");
        assertThat(session.listFiles("notes")).containsExactly(new SandboxFile("notes/a.txt", 5));
        assertThat(session.deleteFile("notes/a.txt")).isTrue();

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(agentKit, org.mockito.Mockito.times(4))
                .runCodeInSession(
                        eq("tool-1"),
                        eq("logical-1"),
                        code.capture(),
                        eq("python3"),
                        anyInt(),
                        eq(1800));
        assertThat(code.getAllValues()).allMatch(value -> value.contains("/workspace"));
        assertThat(code.getAllValues().get(0)).doesNotContain("notes/a.txt", "hello");
        client.close();
    }

    @Test
    void rejectsTraversalAndOversizedFilesBeforeRemoteCalls() {
        AgentKitWrapper agentKit = mock(AgentKitWrapper.class);
        SandboxClient client = new SandboxClient(agentKit, "tool-1");
        SandboxSession session =
                new SandboxSession(client, session("physical-1", "logical-1"), "logical-1", 1800);

        assertThatThrownBy(() -> session.readFile("../secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escape");
        assertThatThrownBy(() -> session.writeFile("/tmp/secret", "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative");
        assertThatThrownBy(
                        () ->
                                session.writeFile(
                                        "large.bin",
                                        new byte[SandboxSession.DEFAULT_MAX_FILE_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
        verify(agentKit, never())
                .runCodeInSession(
                        anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
        client.close();
    }

    private static AgentKitSession session(String sessionId, String logicalId) {
        return AgentKitSession.of(
                sessionId,
                logicalId,
                "Ready",
                "https://sandbox.example",
                "",
                "2026-10-15T00:00:00Z",
                "2026-10-16T00:00:00Z");
    }
}
