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

import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Backend-neutral knowledge-base facade aligned with the Python VeADK contract. */
public final class KnowledgeBase implements BaseKnowledgebaseService, AutoCloseable {

    public static final String DEFAULT_NAME = "user_knowledgebase";
    public static final String DEFAULT_DESCRIPTION =
            "This knowledgebase stores some user-related information.";

    private final String name;
    private final String description;
    private final KnowledgebaseBackend backend;
    private final int topK;

    public KnowledgeBase(String index) {
        this(new InMemoryKnowledgebaseBackend(index), DEFAULT_NAME, DEFAULT_DESCRIPTION, 10);
    }

    public KnowledgeBase(KnowledgebaseBackend backend) {
        this(backend, DEFAULT_NAME, DEFAULT_DESCRIPTION, 10);
    }

    public KnowledgeBase(KnowledgebaseBackend backend, String name, String description, int topK) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.name = requireText(name, "name");
        this.description = Objects.requireNonNullElse(description, "");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        this.topK = topK;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String index() {
        return backend.index();
    }

    public String backend() {
        return backend.backendName();
    }

    public int topK() {
        return topK;
    }

    public boolean addFromText(String text) {
        return addFromText(List.of(text));
    }

    public boolean addFromText(List<String> texts) {
        return backend.add(texts.stream().map(KnowledgebaseEntry::new).toList());
    }

    public boolean addFromFiles(List<Path> files) {
        List<KnowledgebaseEntry> entries = new ArrayList<>();
        for (Path file : files) {
            try {
                entries.add(
                        new KnowledgebaseEntry(
                                Files.readString(file, StandardCharsets.UTF_8),
                                Map.of("file_path", file.toAbsolutePath().normalize().toString())));
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                        "Unable to read knowledge file: " + file, exception);
            }
        }
        return backend.add(entries);
    }

    public boolean addFromDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Knowledge directory does not exist: " + directory);
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            return addFromFiles(paths.filter(Files::isRegularFile).sorted().toList());
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Unable to read knowledge directory: " + directory, exception);
        }
    }

    public List<KnowledgebaseEntry> search(String query) {
        return search(query, topK);
    }

    public List<KnowledgebaseEntry> search(String query, int topK) {
        return List.copyOf(backend.search(Objects.requireNonNull(query, "query"), topK));
    }

    @Override
    public Single<SearchKnowledgebaseResponse> searchKnowledgebase(String query) {
        return Single.fromCallable(
                () -> {
                    List<com.volcengine.veadk.integration.vikingknowledgebase.KnowledgebaseEntry>
                            legacyEntries =
                                    search(query).stream()
                                            .map(KnowledgeBase::toLegacyEntry)
                                            .toList();
                    SearchKnowledgebaseResponse response = new SearchKnowledgebaseResponse();
                    response.setKnowledgebaseEntries(legacyEntries);
                    return response;
                });
    }

    @Override
    public void close() {
        backend.close();
    }

    private static com.volcengine.veadk.integration.vikingknowledgebase.KnowledgebaseEntry
            toLegacyEntry(KnowledgebaseEntry entry) {
        Map<String, String> metadata = new LinkedHashMap<>();
        entry.metadata().forEach((key, value) -> metadata.put(key, String.valueOf(value)));
        return new com.volcengine.veadk.integration.vikingknowledgebase.KnowledgebaseEntry(
                entry.content(), metadata);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
