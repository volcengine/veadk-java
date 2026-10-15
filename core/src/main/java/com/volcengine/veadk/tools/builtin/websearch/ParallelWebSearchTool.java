/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 * Licensed under the Apache License, Version 2.0.
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** Runs independent web-search queries concurrently and preserves input order. */
public final class ParallelWebSearchTool extends BaseTool {

    private static final int MAX_QUERIES = 20;
    private final Function<String, List<String>> searcher;

    public ParallelWebSearchTool() {
        this(defaultSearcher());
    }

    ParallelWebSearchTool(Function<String, List<String>> searcher) {
        super("parallel_web_search", "Search multiple queries from websites in parallel.");
        this.searcher = searcher;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(
                FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(
                                Schema.builder()
                                        .type("OBJECT")
                                        .properties(
                                                ImmutableMap.of(
                                                        "queries",
                                                        Schema.builder()
                                                                .type("ARRAY")
                                                                .items(
                                                                        Schema.builder()
                                                                                .type("STRING")
                                                                                .build())
                                                                .build()))
                                        .required("queries")
                                        .build())
                        .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.fromCallable(() -> execute(parseQueries(args.get("queries"))));
    }

    private Map<String, Object> execute(List<String> queries) throws InterruptedException {
        if (queries.isEmpty()) {
            return Map.of("error", "queries must contain at least one non-blank query");
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(queries.size(), 8));
        try {
            List<CompletableFuture<List<String>>> futures =
                    queries.stream()
                            .map(
                                    query ->
                                            CompletableFuture.supplyAsync(
                                                    () -> {
                                                        try {
                                                            return searcher.apply(query);
                                                        } catch (RuntimeException failure) {
                                                            return List.of(
                                                                    "Web search failed: "
                                                                            + safeMessage(failure));
                                                        }
                                                    },
                                                    executor))
                            .toList();
            LinkedHashMap<String, Object> results = new LinkedHashMap<>();
            for (int index = 0; index < queries.size(); index++) {
                results.put(queries.get(index), futures.get(index).join());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<String> parseQueries(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> queries = new ArrayList<>();
        for (Object item : values) {
            String query = item == null ? "" : String.valueOf(item).strip();
            if (!query.isEmpty() && !queries.contains(query)) {
                queries.add(query);
            }
            if (queries.size() == MAX_QUERIES) {
                break;
            }
        }
        return List.copyOf(queries);
    }

    private static Function<String, List<String>> defaultSearcher() {
        String accessKey =
                firstNonBlank("TOOL_WEB_SEARCH_ACCESS_KEY", EnvUtil.getOptionalAccessKey());
        String secretKey =
                firstNonBlank("TOOL_WEB_SEARCH_SECRET_KEY", EnvUtil.getOptionalSecretKey());
        if (accessKey == null || secretKey == null) {
            throw new IllegalStateException(
                    "Set TOOL_WEB_SEARCH_ACCESS_KEY/TOOL_WEB_SEARCH_SECRET_KEY or"
                            + " VOLCENGINE_ACCESS_KEY/VOLCENGINE_SECRET_KEY");
        }
        String sessionToken = EnvUtil.getSessionToken();
        return query -> {
            try {
                return new WebSearchWrapper(accessKey, secretKey, sessionToken).doWebSearch(query);
            } catch (Exception failure) {
                throw new IllegalStateException(failure.getMessage(), failure);
            }
        };
    }

    private static String firstNonBlank(String environmentName, String fallback) {
        String value = System.getenv(environmentName);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String safeMessage(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }
}
