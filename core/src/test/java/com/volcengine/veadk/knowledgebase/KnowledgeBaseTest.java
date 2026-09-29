package com.volcengine.veadk.knowledgebase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.volcengine.veadk.integration.vikingknowledgebase.VikingKnowledgebaseWrapper;
import com.volcengine.veadk.knowledgebase.backends.BaseKnowledgebaseBackend;
import com.volcengine.veadk.utils.EnvUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class KnowledgeBaseTest {

    @Test
    void builder_requiresIndexOrAppNameForNamedBackend() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgeBase.builder().build());
    }

    @Test
    void addAndSearch_delegateToBackendInstance() throws IOException {
        FakeBackend backend = new FakeBackend();
        KnowledgeBase knowledgeBase =
                KnowledgeBase.builder().backendInstance(backend).topK(3).build();

        knowledgeBase.addFromText("hello");
        List<KnowledgebaseEntry> entries = knowledgeBase.search("question");

        assertEquals(List.of("hello"), backend.texts);
        assertEquals("question", backend.query);
        assertEquals(3, backend.topK);
        assertEquals("answer", entries.get(0).getContent());
    }

    @Test
    void asyncMethods_keepToolLayerCompatibility() {
        FakeBackend backend = new FakeBackend();
        KnowledgeBase knowledgeBase =
                KnowledgeBase.builder().backendInstance(backend).topK(3).build();

        knowledgeBase.addFromTextAsync("hello").blockingAwait();
        SearchKnowledgebaseResponse response =
                knowledgeBase.searchKnowledgebase("question").blockingGet();

        assertEquals(List.of("hello"), backend.texts);
        assertEquals("question", backend.query);
        assertEquals(3, backend.topK);
        assertEquals("answer", response.getKnowledgebaseEntries().get(0).getContent());
    }

    @Test
    void close_delegatesToBackend() throws IOException {
        FakeBackend backend = new FakeBackend();
        KnowledgeBase knowledgeBase = KnowledgeBase.builder().backendInstance(backend).build();

        knowledgeBase.close();

        assertTrue(backend.closed);
    }

    @Test
    void addDoc_delegatesToBackendInstance() throws IOException {
        FakeBackend backend = new FakeBackend();
        KnowledgeBase knowledgeBase = KnowledgeBase.builder().backendInstance(backend).build();

        assertTrue(knowledgeBase.addDoc("tos://bucket/doc.md"));

        assertEquals("tos://bucket/doc.md", backend.documentUri);
    }

    @Test
    void builderAcceptsAppNameAsIndexFallback() throws IOException {
        KnowledgeBase knowledgeBase =
                KnowledgeBase.builder()
                        .backendInstance(new FakeBackend())
                        .appName("ignored_for_instance")
                        .build();

        assertTrue(knowledgeBase.search("q").size() == 1);
    }

    @Test
    void builderCreatesVikingBackend() throws IOException {
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<VikingKnowledgebaseWrapper> mockedCtor =
                        Mockito.mockConstruction(
                                VikingKnowledgebaseWrapper.class,
                                (mock, context) -> {
                                    Mockito.when(mock.isCollectionExists("KbApp")).thenReturn(true);
                                    Mockito.when(
                                                    mock.searchKnowledge(
                                                            "KbApp", "q", 4, null, true, 3))
                                            .thenReturn(
                                                    List.of(
                                                            vikingEntry(
                                                                    "viking answer", Map.of())));
                                })) {
            mockedEnv.when(EnvUtil::getOptionalAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getOptionalSecretKey).thenReturn("sk");

            KnowledgeBase knowledgeBase =
                    KnowledgeBase.builder().backend("viking").appName("KbApp").topK(4).build();

            assertEquals("viking answer", knowledgeBase.search("q").get(0).getContent());
        }
    }

    @Test
    void builderPassesExplicitApiKeyToVikingBackend() {
        AtomicReference<List<?>> constructorArguments = new AtomicReference<>();
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<VikingKnowledgebaseWrapper> mockedCtor =
                        Mockito.mockConstruction(
                                VikingKnowledgebaseWrapper.class,
                                (mock, context) -> constructorArguments.set(context.arguments()))) {
            mockedEnv
                    .when(() -> EnvUtil.normalizeOptionalCredential("explicit-key"))
                    .thenReturn("explicit-key");
            mockedEnv
                    .when(() -> EnvUtil.getVikingApiKey("explicit-key"))
                    .thenReturn("explicit-key");

            KnowledgeBase.builder()
                    .backend("viking")
                    .appName("KbApp")
                    .apiKey("explicit-key")
                    .build();

            assertEquals(1, mockedCtor.constructed().size());
            assertEquals(3, constructorArguments.get().size());
            assertEquals("explicit-key", constructorArguments.get().get(2));
        }
    }

    @Test
    void builderRejectsApiKeyForNamedNonVikingBackend() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        KnowledgeBase.builder()
                                .backend("opensearch")
                                .index("index")
                                .apiKey("api-key")
                                .build());
    }

    @Test
    void builderIgnoresApiKeyForCustomBackendInstance() {
        FakeBackend backend = new FakeBackend();

        KnowledgeBase knowledgeBase =
                KnowledgeBase.builder().backendInstance(backend).apiKey("api-key").build();

        assertEquals(
                "answer",
                knowledgeBase
                        .searchKnowledgebase("q")
                        .blockingGet()
                        .getKnowledgebaseEntries()
                        .get(0)
                        .getContent());
    }

    private static com.volcengine.veadk.integration.vikingknowledgebase.KnowledgebaseEntry
            vikingEntry(String content, Map<String, String> metadata) {
        return new com.volcengine.veadk.integration.vikingknowledgebase.KnowledgebaseEntry(
                content, metadata);
    }

    private static class FakeBackend implements BaseKnowledgebaseBackend {
        private final List<String> texts = new java.util.ArrayList<>();
        private String query;
        private String documentUri;
        private int topK;
        private boolean closed;

        @Override
        public void precheckIndexNaming() {}

        @Override
        public boolean addFromDirectory(Path directory) {
            return true;
        }

        @Override
        public boolean addFromFiles(List<Path> files) {
            return true;
        }

        @Override
        public boolean addFromText(String text) {
            texts.add(text);
            return true;
        }

        @Override
        public boolean addFromText(List<String> text) {
            texts.addAll(text);
            return true;
        }

        @Override
        public boolean addDoc(String documentUri) {
            this.documentUri = documentUri;
            return true;
        }

        @Override
        public List<KnowledgebaseEntry> search(String query, int topK) throws IOException {
            this.query = query;
            this.topK = topK;
            return List.of(new KnowledgebaseEntry("answer", Map.of()));
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
