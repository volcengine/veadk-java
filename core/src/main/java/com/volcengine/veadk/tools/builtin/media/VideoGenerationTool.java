/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. Licensed under Apache 2.0. */
package com.volcengine.veadk.tools.builtin.media;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskResult;
import com.volcengine.ark.runtime.service.ArkService;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Submits asynchronous Ark video-generation tasks. */
public final class VideoGenerationTool extends BaseTool implements AutoCloseable {
    public static final String DEFAULT_MODEL = "doubao-seedance-2-0-260128";
    private ArkService service;
    private final String model;
    private final boolean ownsService;

    public VideoGenerationTool() {
        this(null, ArkMediaServiceFactory.model("MODEL_VIDEO_NAME", DEFAULT_MODEL), true);
    }

    VideoGenerationTool(ArkService service, String model, boolean ownsService) {
        super("video_generate", "Submit a text- or image-guided video generation task.");
        if (!ownsService) this.service = Objects.requireNonNull(service, "service must be set.");
        this.model = Objects.requireNonNull(model, "model must be set.");
        this.ownsService = ownsService;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("prompt", Schema.builder().type("STRING").build());
        properties.put(
                "first_frame",
                Schema.builder()
                        .type("STRING")
                        .description("Optional first-frame image URL.")
                        .build());
        properties.put(
                "last_frame",
                Schema.builder()
                        .type("STRING")
                        .description("Optional last-frame image URL.")
                        .build());
        properties.put(
                "reference_images",
                Schema.builder()
                        .type("ARRAY")
                        .items(Schema.builder().type("STRING").build())
                        .build());
        return Optional.of(
                FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(
                                Schema.builder()
                                        .type("OBJECT")
                                        .properties(properties)
                                        .required("prompt")
                                        .build())
                        .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
        return Single.fromCallable(() -> submit(args, context));
    }

    private Map<String, Object> submit(Map<String, Object> args, ToolContext context) {
        String prompt = text(args.get("prompt"));
        if (prompt.isBlank()) return Map.of("error", "prompt must be set");
        List<CreateContentGenerationTaskRequest.Content> content = new ArrayList<>();
        content.add(new CreateContentGenerationTaskRequest.Content("text", prompt, null, null));
        addImage(content, text(args.get("first_frame")), "first_frame");
        addImage(content, text(args.get("last_frame")), "last_frame");
        if (args.get("reference_images") instanceof List<?> references) {
            references.stream()
                    .map(VideoGenerationTool::text)
                    .filter(value -> !value.isBlank())
                    .forEach(value -> addImage(content, value, "reference_image"));
        }
        CreateContentGenerationTaskRequest request =
                new CreateContentGenerationTaskRequest(model, content);
        CreateContentGenerationTaskResult result = service().createContentGenerationTask(request);
        if (result == null || result.getId() == null || result.getId().isBlank())
            return Map.of("error", "Ark returned no video task ID");
        if (context != null) context.state().put(result.getId() + "_status", "submitted");
        return Map.of("status", "submitted", "task_id", result.getId(), "model", model);
    }

    private static void addImage(
            List<CreateContentGenerationTaskRequest.Content> content, String url, String role) {
        if (url.isBlank()) return;
        content.add(
                new CreateContentGenerationTaskRequest.Content(
                        "image_url",
                        null,
                        new CreateContentGenerationTaskRequest.ImageUrl(url),
                        role));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
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
