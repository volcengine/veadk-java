package com.volcengine.veadk.tools.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.volcengine.veadk.integration.agentkit.AgentKitWrapper;
import com.volcengine.veadk.utils.EnvUtil;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class RunCodeToolTest {

    @Test
    void testDeclaration() {
        try (MockedStatic<EnvUtil> envUtilMock = mockStatic(EnvUtil.class);
                MockedConstruction<AgentKitWrapper> ignored =
                        mockConstruction(AgentKitWrapper.class)) {

            mockEnv(envUtilMock);
            RunCodeTool runCodeTool = new RunCodeTool();

            Optional<FunctionDeclaration> declarationOpt = runCodeTool.declaration();
            assertThat(declarationOpt).isPresent();

            FunctionDeclaration declaration = declarationOpt.get();
            assertThat(declaration.name()).isEqualTo(Optional.of("run_code"));

            // Check parameters
            assertThat(declaration.parameters()).isPresent();
            assertThat(declaration.parameters().get().type().get().toString()).isEqualTo("OBJECT");
            assertThat(declaration.parameters().get().properties().get()).containsKey("timeout");
        }
    }

    @Test
    void testRunAsync_Success() throws Exception {
        try (MockedStatic<EnvUtil> envUtilMock = mockStatic(EnvUtil.class);
                MockedConstruction<AgentKitWrapper> wrapperCtor =
                        mockConstruction(
                                AgentKitWrapper.class,
                                (mock, context) -> {
                                    when(mock.runCode(
                                                    eq("test-tool-id"),
                                                    eq("test-session"),
                                                    eq("print('hello')"),
                                                    eq("python3"),
                                                    anyInt()))
                                            .thenReturn("Hello World");
                                })) {

            mockEnv(envUtilMock);
            RunCodeTool runCodeTool = new RunCodeTool();

            // Mock ToolContext
            ToolContext context = mock(ToolContext.class);
            when(context.sessionId()).thenReturn("test-session");

            // Execute
            Map<String, Object> args =
                    ImmutableMap.of("code", "print('hello')", "language", "python3");
            Map<String, Object> result = runCodeTool.runAsync(args, context).blockingGet();

            // Verify
            assertThat(result).containsEntry("result", "Hello World");
            assertThat(wrapperCtor.constructed()).hasSize(1);
        }
    }

    @Test
    void testRunAsync_Failure() throws Exception {
        try (MockedStatic<EnvUtil> envUtilMock = mockStatic(EnvUtil.class);
                MockedConstruction<AgentKitWrapper> wrapperCtor =
                        mockConstruction(
                                AgentKitWrapper.class,
                                (mock, context) -> {
                                    when(mock.runCode(
                                                    anyString(),
                                                    eq("test-session"),
                                                    eq("bad code"),
                                                    eq("python3"),
                                                    anyInt()))
                                            .thenThrow(new RuntimeException("API Error"));
                                })) {

            mockEnv(envUtilMock);
            RunCodeTool runCodeTool = new RunCodeTool();

            // Mock ToolContext
            ToolContext context = mock(ToolContext.class);
            when(context.sessionId()).thenReturn("test-session");

            // Execute
            Map<String, Object> args = ImmutableMap.of("code", "bad code", "language", "python3");
            Map<String, Object> result = runCodeTool.runAsync(args, context).blockingGet();

            // Verify error handling
            assertThat(result).containsKey("error");
            assertThat(result.get("error").toString()).contains("API Error");
            assertThat(wrapperCtor.constructed()).hasSize(1);
        }
    }

    private void mockEnv(MockedStatic<EnvUtil> envUtilMock) {
        envUtilMock.when(EnvUtil::getAgentKitToolId).thenReturn("test-tool-id");
        envUtilMock.when(EnvUtil::getAgentKitService).thenReturn("agentkit");
        envUtilMock.when(EnvUtil::getAgentKitRegion).thenReturn("cn-beijing");
        envUtilMock.when(EnvUtil::getAgentKitHost).thenReturn("host.com");
        envUtilMock.when(EnvUtil::getAccessKey).thenReturn("ak");
        envUtilMock.when(EnvUtil::getSecretKey).thenReturn("sk");
    }
}
