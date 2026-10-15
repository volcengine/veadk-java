/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 * Licensed under the Apache License, Version 2.0.
 */
package com.volcengine.veadk.tools.builtin.mobile;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.volcengine.veadk.integration.mobile.VolcMobileUseClient;
import com.volcengine.veadk.utils.EnvUtil;
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Executes one or more tasks on a bounded pool of Volcengine cloud phones. */
public final class MobileUseTool extends BaseTool {

    private static final Logger log = LoggerFactory.getLogger(MobileUseTool.class);

    private final Config config;
    private final Client client;
    private final Sleeper sleeper;
    private final LongSupplier nanoTime;
    private final BlockingQueue<String> pods;

    public MobileUseTool(String systemPrompt) {
        this(
                Config.fromEnvironment(systemPrompt).validated(),
                defaultClient(),
                Thread::sleep,
                System::nanoTime);
    }

    MobileUseTool(Config config, Client client, Sleeper sleeper, LongSupplier nanoTime) {
        super("mobile_use", "Execute task prompts on Volcengine cloud phones.");
        this.config = config.validated();
        this.client = client;
        this.sleeper = sleeper;
        this.nanoTime = nanoTime;
        this.pods = new LinkedBlockingQueue<>(config.podIds());
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
                                                        "user_prompts",
                                                        Schema.builder()
                                                                .type("ARRAY")
                                                                .items(
                                                                        Schema.builder()
                                                                                .type("STRING")
                                                                                .build())
                                                                .build()))
                                        .required("user_prompts")
                                        .build())
                        .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.fromCallable(
                () -> {
                    List<String> prompts = parsePrompts(args.get("user_prompts"));
                    if (prompts.isEmpty()) return Map.of("error", "user_prompts must not be empty");
                    return Map.of("results", execute(prompts));
                });
    }

    private List<String> execute(List<String> prompts) throws InterruptedException {
        ExecutorService executor =
                Executors.newFixedThreadPool(Math.min(prompts.size(), config.podIds().size()));
        try {
            List<CompletableFuture<String>> futures =
                    prompts.stream()
                            .map(
                                    prompt ->
                                            CompletableFuture.supplyAsync(
                                                    () -> executeOne(prompt), executor))
                            .toList();
            List<String> results = new ArrayList<>();
            for (CompletableFuture<String> future : futures) results.add(future.join());
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private String executeOne(String prompt) {
        String pod = null;
        String runId = null;
        long deadline = nanoTime.getAsLong() + config.timeout().toNanos();
        try {
            long waitMillis = Math.max(1, config.timeout().toMillis());
            pod = pods.poll(waitMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (pod == null) return "task failed: timed out waiting for a cloud phone";
            runId =
                    client.start(
                            config.productId(),
                            pod,
                            config.systemPrompt(),
                            prompt,
                            config.maxSteps(),
                            config.stepIntervalSeconds(),
                            Math.max(1, Math.toIntExact(config.timeout().toSeconds())));
            while (nanoTime.getAsLong() < deadline) {
                Result result = client.result(runId);
                if (result.status() == 1) return "task success: " + result.content();
                if (result.status() == 2) return "task failed: " + result.content();
                sleeper.sleep(config.pollInterval().toMillis());
            }
            return "task failed: timed out after " + config.timeout().toSeconds() + " seconds";
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return "task failed: interrupted";
        } catch (RuntimeException failure) {
            return "task failed: " + safeMessage(failure);
        } finally {
            if (runId != null) {
                try {
                    client.cancel(runId);
                } catch (RuntimeException failure) {
                    // The original task result remains authoritative; cancellation is best-effort.
                    log.warn("Failed to cancel completed mobile task {}", runId, failure);
                }
            }
            if (pod != null) pods.offer(pod);
        }
    }

    private static List<String> parsePrompts(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream()
                .map(item -> item == null ? "" : String.valueOf(item).strip())
                .filter(item -> !item.isEmpty())
                .limit(50)
                .toList();
    }

    private static Client defaultClient() {
        VolcMobileUseClient delegate =
                new VolcMobileUseClient(
                        EnvUtil.getAccessKey(), EnvUtil.getSecretKey(), EnvUtil.getSessionToken());
        return new Client() {
            public String start(
                    String productId,
                    String podId,
                    String systemPrompt,
                    String userPrompt,
                    int maxSteps,
                    int stepIntervalSeconds,
                    int timeoutSeconds) {
                return delegate.start(
                        productId,
                        podId,
                        systemPrompt,
                        userPrompt,
                        maxSteps,
                        stepIntervalSeconds,
                        timeoutSeconds);
            }

            public Result result(String runId) {
                var result = delegate.result(runId);
                return new Result(result.status(), result.content());
            }

            public void cancel(String runId) {
                delegate.cancel(runId);
            }
        };
    }

    private static String safeMessage(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    record Config(
            String productId,
            List<String> podIds,
            String systemPrompt,
            Duration timeout,
            int maxSteps,
            int stepIntervalSeconds,
            Duration pollInterval) {
        private static Config fromEnvironment(String systemPrompt) {
            List<String> ids = parseToolIds(System.getenv("TOOL_MOBILE_USE_TOOL_ID"));
            if (ids.isEmpty()) {
                return new Config(
                        "",
                        List.of(),
                        systemPrompt,
                        Duration.ofMinutes(15),
                        100,
                        1,
                        Duration.ofSeconds(5));
            }
            int separator = ids.get(0).indexOf('-');
            String product = separator < 1 ? "" : ids.get(0).substring(0, separator);
            List<String> pods =
                    ids.stream()
                            .map(
                                    id ->
                                            id.startsWith(product + "-")
                                                    ? id.substring(product.length() + 1)
                                                    : "")
                            .filter(id -> !id.isBlank())
                            .toList();
            return new Config(
                    product,
                    pods,
                    systemPrompt,
                    Duration.ofMinutes(15),
                    100,
                    1,
                    Duration.ofSeconds(5));
        }

        private Config validated() {
            if (productId == null || productId.isBlank() || podIds == null || podIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "TOOL_MOBILE_USE_TOOL_ID must contain productId-podId values");
            }
            if (systemPrompt == null || systemPrompt.isBlank()) {
                throw new IllegalArgumentException("systemPrompt must be set");
            }
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (maxSteps < 1 || stepIntervalSeconds < 1) {
                throw new IllegalArgumentException(
                        "maxSteps and stepIntervalSeconds must be positive");
            }
            if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
                throw new IllegalArgumentException("pollInterval must be positive");
            }
            return this;
        }

        private static List<String> parseToolIds(String raw) {
            if (raw == null || raw.isBlank()) return List.of();
            String normalized = raw.strip().replaceAll("^[\\[]|[\\]]$", "");
            return java.util.Arrays.stream(normalized.split(","))
                    .map(value -> value.strip().replaceAll("^[\"']|[\"']$", ""))
                    .filter(value -> !value.isBlank())
                    .toList();
        }
    }

    interface Client {
        String start(
                String productId,
                String podId,
                String systemPrompt,
                String userPrompt,
                int maxSteps,
                int stepIntervalSeconds,
                int timeoutSeconds);

        Result result(String runId);

        void cancel(String runId);
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    record Result(int status, String content) {}
}
