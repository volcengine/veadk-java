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
package com.volcengine.veadk.tools.builtin.websearch;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.volcengine.veadk.integration.websearch.WebSearchWrapper;
import com.volcengine.veadk.utils.EnvUtil;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSearchTool extends BaseTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private WebSearchWrapper webSearchWrapper;

    public WebSearchTool() {
        super("web_search", "web_search");
        webSearchWrapper = new WebSearchWrapper(EnvUtil.getAccessKey(), EnvUtil.getSecretKey());
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(
                FunctionDeclaration.builder()
                        .name(this.name())
                        .description(this.description())
                        .parameters(
                                Schema.builder()
                                        .type("OBJECT")
                                        .properties(
                                                ImmutableMap.of(
                                                        "query",
                                                        Schema.builder().type("STRING").build()))
                                        .required("query")
                                        .build())
                        .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.fromCallable(
                () -> {
                    List<String> searchResult = null;
                    try {
                        searchResult =
                                webSearchWrapper.doWebSearch(String.valueOf(args.get("query")));
                    } catch (Exception e) {
                        log.error("doWebSearch failed", e);
                        return ImmutableMap.of("Status", "failed", "ErrorMessage", e.getMessage());
                    }

                    return ImmutableMap.of("Status", "success", "searchResult", searchResult);
                });
    }
}
