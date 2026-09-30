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

import com.volcengine.veadk.knowledgebase.backends.BaseKnowledgebaseBackend;
import com.volcengine.veadk.knowledgebase.backends.opensearch.OpensearchKnowledgebaseBackend;
import com.volcengine.veadk.knowledgebase.backends.viking.VikingKnowledgebaseBackend;
import com.volcengine.veadk.knowledgebase.backends.viking.VikingKnowledgebaseConfig;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

public class KnowledgeBase implements BaseKnowledgebaseService, AutoCloseable {

    private final BaseKnowledgebaseBackend backend;
    private final int topK;

    private KnowledgeBase(BaseKnowledgebaseBackend backend, int topK) {
        this.backend = backend;
        this.topK = topK;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static KnowledgeBase opensearch(String index) {
        return builder().backend("opensearch").index(index).build();
    }

    public static KnowledgeBase viking(String appName) {
        return builder().backend("viking").appName(appName).build();
    }

    public boolean addFromDirectory(String directory) throws IOException {
        return backend.addFromDirectory(Path.of(directory));
    }

    public boolean addFromFiles(List<String> files) throws IOException {
        return backend.addFromFiles(files.stream().map(Path::of).toList());
    }

    public boolean addFromText(String text) throws IOException {
        return backend.addFromText(text);
    }

    public boolean addFromText(List<String> text) throws IOException {
        return backend.addFromText(text);
    }

    public boolean addDoc(String documentUri) throws IOException {
        return backend.addDoc(documentUri);
    }

    public List<KnowledgebaseEntry> search(String query) throws IOException {
        return backend.search(query, topK);
    }

    public Completable addFromDirectoryAsync(String directory) {
        return Completable.fromAction(() -> addFromDirectory(directory));
    }

    public Completable addFromFilesAsync(List<String> files) {
        return Completable.fromAction(() -> addFromFiles(files));
    }

    public Completable addFromTextAsync(String text) {
        return Completable.fromAction(() -> addFromText(text));
    }

    public Completable addFromTextAsync(List<String> text) {
        return Completable.fromAction(() -> addFromText(text));
    }

    public Completable addDocAsync(String documentUri) {
        return Completable.fromAction(() -> addDoc(documentUri));
    }

    public Single<List<KnowledgebaseEntry>> searchAsync(String query) {
        return Single.fromCallable(() -> backend.search(query, topK));
    }

    @Override
    public Single<SearchKnowledgebaseResponse> searchKnowledgebase(String query) {
        return searchAsync(query)
                .map(
                        entries -> {
                            SearchKnowledgebaseResponse response =
                                    new SearchKnowledgebaseResponse();
                            response.setKnowledgebaseEntries(entries);
                            return response;
                        });
    }

    @Override
    public void close() throws IOException {
        backend.close();
    }

    public static class Builder {
        private String backend = "opensearch";
        private String appName = "";
        private String index = "";
        private int topK = 10;
        private BaseKnowledgebaseBackend backendInstance;
        private VikingKnowledgebaseConfig vikingConfig;

        public Builder backend(String backend) {
            this.backend = backend;
            return this;
        }

        public Builder appName(String appName) {
            this.appName = appName;
            return this;
        }

        public Builder index(String index) {
            this.index = index;
            return this;
        }

        public Builder topK(int topK) {
            if (topK <= 0) {
                throw new IllegalArgumentException("topK must be positive.");
            }
            this.topK = topK;
            return this;
        }

        public Builder backendInstance(BaseKnowledgebaseBackend backendInstance) {
            this.backendInstance = backendInstance;
            return this;
        }

        public Builder vikingConfig(VikingKnowledgebaseConfig vikingConfig) {
            this.vikingConfig = vikingConfig;
            return this;
        }

        public KnowledgeBase build() {
            BaseKnowledgebaseBackend selectedBackend =
                    backendInstance == null ? createBackend() : backendInstance;
            return new KnowledgeBase(selectedBackend, topK);
        }

        private BaseKnowledgebaseBackend createBackend() {
            String resolvedIndex = StringUtils.defaultIfBlank(index, appName);
            if (StringUtils.isBlank(resolvedIndex)) {
                throw new IllegalArgumentException("Either index or appName must be provided.");
            }
            if ("opensearch".equalsIgnoreCase(backend)) {
                return new OpensearchKnowledgebaseBackend(resolvedIndex);
            }
            if ("viking".equalsIgnoreCase(backend)) {
                return vikingConfig == null
                        ? new VikingKnowledgebaseBackend(resolvedIndex)
                        : new VikingKnowledgebaseBackend(resolvedIndex, vikingConfig);
            }
            throw new IllegalArgumentException("Unsupported knowledgebase backend: " + backend);
        }
    }
}
