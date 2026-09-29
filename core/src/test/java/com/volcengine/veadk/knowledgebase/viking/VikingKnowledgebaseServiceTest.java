package com.volcengine.veadk.knowledgebase.viking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.volcengine.veadk.integration.vikingknowledgebase.KnowledgebaseEntry;
import com.volcengine.veadk.integration.vikingknowledgebase.VikingKnowledgebaseWrapper;
import com.volcengine.veadk.knowledgebase.SearchKnowledgebaseResponse;
import com.volcengine.veadk.utils.EnvUtil;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class VikingKnowledgebaseServiceTest {

    @Test
    void constructor_invalidAppName_shouldThrow_and_notConstructWrapper() {
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<VikingKnowledgebaseWrapper> mockedCtor =
                        Mockito.mockConstruction(VikingKnowledgebaseWrapper.class)) {
            mockedEnv.when(EnvUtil::getOptionalAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getOptionalSecretKey).thenReturn("sk");

            assertThrows(
                    IllegalArgumentException.class, () -> new VikingKnowledgebaseService("9bad"));
            assertEquals(0, mockedCtor.constructed().size());
        }
    }

    @Test
    void constructor_validApp_collectionExists_shouldNotCreate() {
        String appName = "MyApp";
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<VikingKnowledgebaseWrapper> mockedCtor =
                        Mockito.mockConstruction(
                                VikingKnowledgebaseWrapper.class,
                                (mock, context) -> {
                                    Mockito.when(mock.isCollectionExists(appName)).thenReturn(true);
                                })) {
            mockedEnv.when(EnvUtil::getOptionalAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getOptionalSecretKey).thenReturn("sk");

            VikingKnowledgebaseService service = new VikingKnowledgebaseService(appName);
            VikingKnowledgebaseWrapper wrapperMock = mockedCtor.constructed().get(0);

            verify(wrapperMock).isCollectionExists(appName);
            verify(wrapperMock, never()).createCollection(appName);
            mockedEnv.verify(EnvUtil::getOptionalAccessKey);
            mockedEnv.verify(EnvUtil::getOptionalSecretKey);
        }
    }

    @Test
    void constructor_validApp_collectionMissing_shouldCreate() {
        String appName = "AppX";
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<VikingKnowledgebaseWrapper> mockedCtor =
                        Mockito.mockConstruction(
                                VikingKnowledgebaseWrapper.class,
                                (mock, context) -> {
                                    Mockito.when(mock.isCollectionExists(appName))
                                            .thenReturn(false);
                                })) {
            mockedEnv.when(EnvUtil::getOptionalAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getOptionalSecretKey).thenReturn("sk");

            VikingKnowledgebaseService service = new VikingKnowledgebaseService(appName);
            VikingKnowledgebaseWrapper wrapperMock = mockedCtor.constructed().get(0);

            verify(wrapperMock).isCollectionExists(appName);
            verify(wrapperMock).createCollection(appName);
        }
    }

    @Test
    void searchKnowledgebase_returnsResponseWithEntries_and_callsWrapperWithExpectedArgs() {
        String appName = "KbApp";
        List<KnowledgebaseEntry> mockEntries =
                Collections.singletonList(new KnowledgebaseEntry("content1", Map.of("k", "v")));

        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<VikingKnowledgebaseWrapper> mockedCtor =
                        Mockito.mockConstruction(
                                VikingKnowledgebaseWrapper.class,
                                (mock, context) -> {
                                    Mockito.when(mock.isCollectionExists(appName)).thenReturn(true);
                                    Mockito.when(
                                                    mock.searchKnowledge(
                                                            appName, "q", 5, null, true, 3))
                                            .thenReturn(mockEntries);
                                })) {
            mockedEnv.when(EnvUtil::getOptionalAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getOptionalSecretKey).thenReturn("sk");

            VikingKnowledgebaseService service = new VikingKnowledgebaseService(appName);
            SearchKnowledgebaseResponse response = service.searchKnowledgebase("q").blockingGet();

            assertEquals(1, response.getKnowledgebaseEntries().size());
            assertEquals("content1", response.getKnowledgebaseEntries().get(0).getContent());

            VikingKnowledgebaseWrapper wrapperMock = mockedCtor.constructed().get(0);
            verify(wrapperMock).searchKnowledge(appName, "q", 5, null, true, 3);
        }
    }
}
