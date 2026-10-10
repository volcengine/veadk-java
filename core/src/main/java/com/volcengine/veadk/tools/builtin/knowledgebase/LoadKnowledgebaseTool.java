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
package com.volcengine.veadk.tools.builtin.knowledgebase;

import com.google.adk.models.LlmRequest;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.volcengine.veadk.knowledgebase.BaseKnowledgebaseService;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.lang.reflect.Method;
import java.util.Objects;

public class LoadKnowledgebaseTool extends FunctionTool {

    private final BaseKnowledgebaseService knowledgebaseService;

    private static Method getLoadKnowledgeMethod() {
        try {
            return KnowledgebaseLoader.class.getMethod(
                    "loadKnowledgebase", String.class, ToolContext.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Failed to load knowledge method.", e);
        }
    }

    public LoadKnowledgebaseTool(BaseKnowledgebaseService knowledgebaseService) {
        super(
                /* instance= */ new KnowledgebaseLoader(knowledgebaseService),
                getLoadKnowledgeMethod(),
                /* isLongRunning= */ false,
                /* requireConfirmation= */ false);
        this.knowledgebaseService =
                Objects.requireNonNull(knowledgebaseService, "knowledgebaseService must be set.");
    }

    public Single<LoadKnowledgebaseResponse> loadKnowledgebase(
            @Annotations.Schema(name = "query") String query,
            @Annotations.Schema(name = "toolContext") ToolContext toolContext) {
        return knowledgebaseService
                .searchKnowledgebase(query)
                .map(
                        searchKnowledgebaseResponse ->
                                new LoadKnowledgebaseResponse(
                                        searchKnowledgebaseResponse.getKnowledgebaseEntries()));
    }

    @Override
    public Completable processLlmRequest(
            LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {
        return super.processLlmRequest(llmRequestBuilder, toolContext)
                .doOnComplete(
                        () ->
                                llmRequestBuilder.appendInstructions(
                                        ImmutableList.of(
                                                """
                                                        You have a knowledgebase. You can use it to answer questions. If any questions need you
                                                        to look up the knowledgebase, you should call loadKnowledgebase function with a query.
                                                """)));
    }

    public static final class KnowledgebaseLoader {
        private final BaseKnowledgebaseService knowledgebaseService;

        private KnowledgebaseLoader(BaseKnowledgebaseService knowledgebaseService) {
            this.knowledgebaseService =
                    Objects.requireNonNull(
                            knowledgebaseService, "knowledgebaseService must be set.");
        }

        public Single<LoadKnowledgebaseResponse> loadKnowledgebase(
                @Annotations.Schema(name = "query") String query,
                @Annotations.Schema(name = "toolContext") ToolContext toolContext) {
            return knowledgebaseService
                    .searchKnowledgebase(query)
                    .map(
                            searchKnowledgebaseResponse ->
                                    new LoadKnowledgebaseResponse(
                                            searchKnowledgebaseResponse.getKnowledgebaseEntries()));
        }
    }
}
