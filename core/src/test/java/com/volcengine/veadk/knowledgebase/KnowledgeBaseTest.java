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
package com.volcengine.veadk.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.volcengine.veadk.integration.vikingknowledgebase.KnowledgebaseEntry;
import io.reactivex.rxjava3.core.Single;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeBaseTest {

    @TempDir Path directory;

    @Test
    void inMemoryFacadeAddsAndRanksTextDeterministically() {
        KnowledgeBase knowledgeBase = new KnowledgeBase("docs");
        knowledgeBase.addFromText(
                List.of(
                        "Java reactive streams and Flowable",
                        "Python data science",
                        "Java build compatibility"));

        List<com.volcengine.veadk.knowledgebase.KnowledgebaseEntry> results =
                knowledgeBase.search("Java reactive", 2);

        assertThat(results)
                .extracting(com.volcengine.veadk.knowledgebase.KnowledgebaseEntry::content)
                .containsExactly("Java reactive streams and Flowable", "Java build compatibility");
        assertThat(knowledgeBase.index()).isEqualTo("docs");
    }

    @Test
    void facadeLoadsFilesAndKeepsSourceMetadata() throws Exception {
        Path nested = Files.createDirectories(directory.resolve("nested"));
        Files.writeString(directory.resolve("one.txt"), "first document");
        Files.writeString(nested.resolve("two.txt"), "second document");
        KnowledgeBase knowledgeBase = new KnowledgeBase("files");

        assertThat(knowledgeBase.addFromDirectory(directory)).isTrue();

        List<com.volcengine.veadk.knowledgebase.KnowledgebaseEntry> results =
                knowledgeBase.search("second", 1);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).metadata().get("file_path").toString()).endsWith("two.txt");
    }

    @Test
    void facadePreservesLegacySearchServiceContract() {
        KnowledgeBase knowledgeBase = new KnowledgeBase("legacy");
        knowledgeBase.addFromText("legacy compatible content");

        SearchKnowledgebaseResponse response =
                knowledgeBase.searchKnowledgebase("compatible").blockingGet();

        assertThat(response.getKnowledgebaseEntries())
                .extracting(KnowledgebaseEntry::getContent)
                .containsExactly("legacy compatible content");
    }

    @Test
    void legacyServiceAdapterSupportsReadOnlyMigration() {
        SearchKnowledgebaseResponse response = new SearchKnowledgebaseResponse();
        response.setKnowledgebaseEntries(
                List.of(new KnowledgebaseEntry("from viking", Map.of("source", "legacy"))));
        BaseKnowledgebaseService service = query -> Single.just(response);
        KnowledgebaseServiceBackendAdapter adapter =
                new KnowledgebaseServiceBackendAdapter("legacy", service);

        assertThat(adapter.search("query", 5))
                .containsExactly(
                        new com.volcengine.veadk.knowledgebase.KnowledgebaseEntry(
                                "from viking", Map.of("source", "legacy")));
        assertThatThrownBy(
                        () ->
                                adapter.add(
                                        List.of(
                                                new com.volcengine.veadk.knowledgebase
                                                        .KnowledgebaseEntry("new"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
