/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. Licensed under Apache 2.0. */
package com.volcengine.veadk.tools.builtin.media;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.volcengine.ark.runtime.model.content.generation.GetContentGenerationTaskRequest;
import com.volcengine.ark.runtime.model.content.generation.GetContentGenerationTaskResponse;
import com.volcengine.ark.runtime.service.ArkService;
import io.reactivex.rxjava3.core.Single;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Queries an asynchronous Ark video-generation task. */
public final class VideoTaskQueryTool extends BaseTool implements AutoCloseable {
    private ArkService service;
    private final boolean ownsService;

    public VideoTaskQueryTool() {
        this(null, true);
    }

    VideoTaskQueryTool(ArkService service, boolean ownsService) {
        super(
                "video_task_query",
                "Query a video generation task and return its output URL when ready.");
        if (!ownsService) this.service = Objects.requireNonNull(service, "service must be set.");
        this.ownsService = ownsService;
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
                                                Map.of(
                                                        "task_id",
                                                        Schema.builder().type("STRING").build()))
                                        .required("task_id")
                                        .build())
                        .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
        return Single.fromCallable(() -> query(args, context));
    }

    private Map<String, Object> query(Map<String, Object> args, ToolContext context) {
        String taskId =
                args.get("task_id") == null ? "" : String.valueOf(args.get("task_id")).trim();
        if (taskId.isBlank()) return Map.of("error", "task_id must be set");
        GetContentGenerationTaskResponse response =
                service().getContentGenerationTask(new GetContentGenerationTaskRequest(taskId));
        if (response == null) return Map.of("error", "Ark returned no video task");
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", taskId);
        result.put("status", response.getStatus());
        result.put("model", response.getModel());
        if (response.getContent() != null && response.getContent().getVideoUrl() != null) {
            String url = response.getContent().getVideoUrl();
            result.put("video_url", url);
            if (context != null) context.state().put(taskId + "_video_url", url);
        }
        if (response.getError() != null) {
            result.put(
                    "error",
                    Map.of(
                            "code",
                            Objects.toString(response.getError().getCode(), ""),
                            "message",
                            Objects.toString(response.getError().getMessage(), "")));
        }
        return result;
    }

    @Override
    public void close() {
        if (ownsService && service != null) service.shutdownExecutor();
    }

    private synchronized ArkService service() {
        if (service == null)
            service = ArkMediaServiceFactory.create("MODEL_VIDEO_API_KEY", "MODEL_VIDEO_API_BASE");
        return service;
    }
}
