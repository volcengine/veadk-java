package com.volcengine.veadk.tools.builtin.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.volcengine.veadk.integration.websearch.WebSearchWrapper;
import com.volcengine.veadk.utils.EnvUtil;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class WebSearchToolTest {

    @Test
    void constructor_callsEnvUtil_and_initializesWrapper() {
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<WebSearchWrapper> mockedCtor =
                        Mockito.mockConstruction(WebSearchWrapper.class)) {
            mockedEnv.when(EnvUtil::getAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getSecretKey).thenReturn("sk");

            WebSearchTool tool = new WebSearchTool();

            assertEquals(1, mockedCtor.constructed().size());
            mockedEnv.verify(EnvUtil::getAccessKey);
            mockedEnv.verify(EnvUtil::getSecretKey);
        }
    }

    @Test
    void runAsync_success_returnsSearchResult() throws Exception {
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<WebSearchWrapper> mockedCtor =
                        Mockito.mockConstruction(
                                WebSearchWrapper.class,
                                (mock, context) -> {
                                    Mockito.when(mock.doWebSearch("cats"))
                                            .thenReturn(List.of("r1", "r2"));
                                })) {
            mockedEnv.when(EnvUtil::getAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getSecretKey).thenReturn("sk");

            WebSearchTool tool = new WebSearchTool();

            Map<String, Object> result =
                    tool.runAsync(Collections.singletonMap("query", "cats"), null).blockingGet();

            assertEquals("success", result.get("Status"));
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) result.get("searchResult");
            assertEquals(List.of("r1", "r2"), list);
        }
    }

    @Test
    void runAsync_error_returnsFailedWithMessage() throws Exception {
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<WebSearchWrapper> mockedCtor =
                        Mockito.mockConstruction(
                                WebSearchWrapper.class,
                                (mock, context) -> {
                                    Mockito.when(mock.doWebSearch("boom"))
                                            .thenThrow(new Exception("boom"));
                                })) {
            mockedEnv.when(EnvUtil::getAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getSecretKey).thenReturn("sk");

            WebSearchTool tool = new WebSearchTool();

            Map<String, Object> result =
                    tool.runAsync(Collections.singletonMap("query", "boom"), null).blockingGet();

            assertEquals("failed", result.get("Status"));
            assertEquals("boom", result.get("ErrorMessage"));
            assertFalse(result.containsKey("searchResult"));
        }
    }

    @Test
    void declaration_hasName_and_parametersSchema() {
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<WebSearchWrapper> ignored =
                        Mockito.mockConstruction(WebSearchWrapper.class)) {
            mockedEnv.when(EnvUtil::getAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getSecretKey).thenReturn("sk");

            WebSearchTool tool = new WebSearchTool();
            Optional<FunctionDeclaration> declOpt = tool.declaration();
            assertTrue(declOpt.isPresent());
            FunctionDeclaration decl = declOpt.get();
            assertEquals("web_search", decl.name().orElse(""));
            Optional<Schema> paramsOpt = decl.parameters();
            assertTrue(paramsOpt.isPresent());
            assertTrue(paramsOpt.get().type().isPresent());
        }
    }
}
