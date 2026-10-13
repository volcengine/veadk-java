package com.volcengine.veadk.knowledgebase.backends.viking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.volcengine.veadk.integration.vikingknowledgebase.VikingKnowledgebaseWrapper;
import com.volcengine.veadk.knowledgebase.KnowledgebaseEntry;
import com.volcengine.veadk.utils.EnvUtil;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class VikingKnowledgebaseBackendTest {

    @Test
    void constructor_invalidCollectionName_shouldThrowBeforeConstructingWrapper() {
        try (MockedStatic<EnvUtil> mockedEnv =
                        Mockito.mockStatic(EnvUtil.class, Mockito.CALLS_REAL_METHODS);
                MockedConstruction<VikingKnowledgebaseWrapper> mockedCtor =
                        Mockito.mockConstruction(VikingKnowledgebaseWrapper.class)) {
            assertThrows(
                    IllegalArgumentException.class, () -> new VikingKnowledgebaseBackend("9bad"));

            assertEquals(0, mockedCtor.constructed().size());
            mockedEnv.verifyNoInteractions();
        }
    }

    @Test
    void constructor_validCollectionMissing_shouldCreateCollection() {
        String collectionName = "KbApp";
        try (MockedStatic<EnvUtil> mockedEnv =
                        Mockito.mockStatic(EnvUtil.class, Mockito.CALLS_REAL_METHODS);
                MockedConstruction<VikingKnowledgebaseWrapper> mockedCtor =
                        Mockito.mockConstruction(
                                VikingKnowledgebaseWrapper.class,
                                (mock, context) -> {
                                    when(mock.isCollectionExists(collectionName)).thenReturn(false);
                                })) {
            mockedEnv.when(EnvUtil::getOptionalAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getOptionalSecretKey).thenReturn("sk");
            mockedEnv.when(EnvUtil::getVikingKnowledgebaseApiKey).thenReturn(null);

            new VikingKnowledgebaseBackend(collectionName);

            VikingKnowledgebaseWrapper wrapper = mockedCtor.constructed().get(0);
            verify(wrapper).isCollectionExists(collectionName);
            verify(wrapper).createCollection(collectionName);
        }
    }

    @Test
    void constructor_validCollectionExists_shouldNotCreateCollection() {
        String collectionName = "KbApp";
        try (MockedStatic<EnvUtil> mockedEnv =
                        Mockito.mockStatic(EnvUtil.class, Mockito.CALLS_REAL_METHODS);
                MockedConstruction<VikingKnowledgebaseWrapper> mockedCtor =
                        Mockito.mockConstruction(
                                VikingKnowledgebaseWrapper.class,
                                (mock, context) -> {
                                    when(mock.isCollectionExists(collectionName)).thenReturn(true);
                                })) {
            mockedEnv.when(EnvUtil::getOptionalAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getOptionalSecretKey).thenReturn("sk");
            mockedEnv.when(EnvUtil::getVikingKnowledgebaseApiKey).thenReturn(null);

            new VikingKnowledgebaseBackend(collectionName);

            VikingKnowledgebaseWrapper wrapper = mockedCtor.constructed().get(0);
            verify(wrapper).isCollectionExists(collectionName);
            verify(wrapper, never()).createCollection(collectionName);
        }
    }

    @Test
    void constructor_apiKeyOnly_shouldSkipCollectionPrecheck() {
        String collectionName = "KbApp";
        VikingKnowledgebaseConfig config =
                VikingKnowledgebaseConfig.builder().apiKey(" api-key ").build();
        AtomicReference<List<?>> constructorArgs = new AtomicReference<>();
        try (MockedConstruction<VikingKnowledgebaseWrapper> mockedCtor =
                Mockito.mockConstruction(
                        VikingKnowledgebaseWrapper.class,
                        (mock, context) -> constructorArgs.set(context.arguments()))) {
            new VikingKnowledgebaseBackend(collectionName, config);

            VikingKnowledgebaseWrapper wrapper = mockedCtor.constructed().get(0);
            verify(wrapper, never()).isCollectionExists(collectionName);
            verify(wrapper, never()).createCollection(collectionName);
            assertEquals("api-key", constructorArgs.get().get(2));
        }
    }

    @Test
    void constructor_withoutApiKeyOrManagementCredentials_shouldThrow() {
        VikingKnowledgebaseConfig config = VikingKnowledgebaseConfig.builder().build();

        assertThrows(
                IllegalStateException.class, () -> new VikingKnowledgebaseBackend("KbApp", config));
    }

    @Test
    void config_legacyConstructorAndBuilderExposeCredentialsAndApiKey() {
        VikingKnowledgebaseConfig legacyConfig =
                new VikingKnowledgebaseConfig("ak", "sk", false, 1);

        assertEquals("ak", legacyConfig.getAccessKey());
        assertEquals("sk", legacyConfig.getSecretKey());
        assertFalse(legacyConfig.hasApiKey());
        assertTrue(legacyConfig.hasManagementCredentials());

        VikingKnowledgebaseConfig config =
                VikingKnowledgebaseConfig.builder()
                        .accessKey("builder-ak")
                        .secretKey("builder-sk")
                        .apiKey(" builder-api-key ")
                        .rerank(false)
                        .chunkDiffusionCount(2)
                        .build();

        assertEquals("builder-ak", config.getAccessKey());
        assertEquals("builder-sk", config.getSecretKey());
        assertEquals("builder-api-key", config.getApiKey());
        assertFalse(config.isRerank());
        assertEquals(2, config.getChunkDiffusionCount());
        assertTrue(config.hasApiKey());
        assertTrue(config.hasManagementCredentials());
    }

    @Test
    void search_convertsVikingEntriesToCommonEntries() throws IOException {
        VikingKnowledgebaseWrapper wrapper = Mockito.mock(VikingKnowledgebaseWrapper.class);
        when(wrapper.isCollectionExists("KbApp")).thenReturn(true);
        when(wrapper.searchKnowledge("KbApp", "q", 7, null, false, 1))
                .thenReturn(List.of(vikingEntry("content1", Map.of("k", "v"))));
        VikingKnowledgebaseBackend backend =
                new VikingKnowledgebaseBackend("KbApp", wrapper, false, 1);

        List<KnowledgebaseEntry> entries = backend.search("q", 7);

        assertEquals(1, entries.size());
        assertEquals("content1", entries.get(0).getContent());
        assertEquals("v", entries.get(0).getMetadata().get("k"));
        verify(wrapper).searchKnowledge("KbApp", "q", 7, null, false, 1);
    }

    @Test
    void search_blankQuery_returnsEmptyListWithoutCallingWrapper() throws IOException {
        VikingKnowledgebaseWrapper wrapper = Mockito.mock(VikingKnowledgebaseWrapper.class);
        when(wrapper.isCollectionExists("KbApp")).thenReturn(true);
        VikingKnowledgebaseBackend backend =
                new VikingKnowledgebaseBackend("KbApp", wrapper, true, 3);

        assertTrue(backend.search(" ", 5).isEmpty());

        verify(wrapper, never()).searchKnowledge("KbApp", " ", 5, null, true, 3);
    }

    @Test
    void addDoc_delegatesToWrapper() {
        VikingKnowledgebaseWrapper wrapper = Mockito.mock(VikingKnowledgebaseWrapper.class);
        when(wrapper.isCollectionExists("KbApp")).thenReturn(true);
        when(wrapper.addDoc("KbApp", "tos://bucket/file")).thenReturn(true);
        VikingKnowledgebaseBackend backend =
                new VikingKnowledgebaseBackend("KbApp", wrapper, true, 3);

        assertTrue(backend.addDoc("tos://bucket/file"));
    }

    @Test
    void localIngestion_isNotSupported() {
        VikingKnowledgebaseWrapper wrapper = Mockito.mock(VikingKnowledgebaseWrapper.class);
        when(wrapper.isCollectionExists("KbApp")).thenReturn(true);
        VikingKnowledgebaseBackend backend =
                new VikingKnowledgebaseBackend("KbApp", wrapper, true, 3);

        assertThrows(UnsupportedOperationException.class, () -> backend.addFromText("text"));
    }

    private static com.volcengine.veadk.integration.vikingknowledgebase.KnowledgebaseEntry
            vikingEntry(String content, Map<String, String> metadata) {
        return new com.volcengine.veadk.integration.vikingknowledgebase.KnowledgebaseEntry(
                content, metadata);
    }
}
