/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. Licensed under Apache 2.0. */
package com.volcengine.veadk.tools.builtin.media;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.volcengine.ark.runtime.model.images.generation.GenerateImagesRequest;
import com.volcengine.ark.runtime.model.images.generation.ImagesResponse;
import com.volcengine.ark.runtime.service.ArkService;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Generates images with Ark Seedream and persists base64 results as ADK artifacts. */
public class ImageGenerationTool extends BaseTool implements AutoCloseable {
    public static final String DEFAULT_MODEL = "doubao-seedream-5-0-260128";
    private ArkService service;
    private final String model;
    private final boolean ownsService;
    private final boolean requiresImage;
    private final String keyEnv;
    private final String baseEnv;

    public ImageGenerationTool() {
        this(
                "image_generate",
                "Generate images from a text prompt.",
                "MODEL_IMAGE_API_KEY",
                "MODEL_IMAGE_API_BASE",
                "MODEL_IMAGE_NAME",
                DEFAULT_MODEL,
                false);
    }

    protected ImageGenerationTool(
            String name,
            String description,
            String keyEnv,
            String baseEnv,
            String modelEnv,
            String fallbackModel,
            boolean requiresImage) {
        super(name, description);
        this.model = ArkMediaServiceFactory.model(modelEnv, fallbackModel);
        this.ownsService = true;
        this.requiresImage = requiresImage;
        this.keyEnv = keyEnv;
        this.baseEnv = baseEnv;
    }

    ImageGenerationTool(
            String name,
            String description,
            ArkService service,
            String model,
            boolean ownsService,
            boolean requiresImage) {
        super(name, description);
        this.service = Objects.requireNonNull(service, "service must be set.");
        this.model = Objects.requireNonNull(model, "model must be set.");
        this.ownsService = ownsService;
        this.requiresImage = requiresImage;
        this.keyEnv = null;
        this.baseEnv = null;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("prompt", Schema.builder().type("STRING").build());
        properties.put(
                "image",
                Schema.builder()
                        .type("STRING")
                        .description("Source image URL or data URL for editing.")
                        .build());
        properties.put("size", Schema.builder().type("STRING").build());
        properties.put(
                "response_format",
                Schema.builder().type("STRING").enum_("url", "b64_json").build());
        properties.put("watermark", Schema.builder().type("BOOLEAN").build());
        properties.put("seed", Schema.builder().type("INTEGER").build());
        properties.put("guidance_scale", Schema.builder().type("NUMBER").build());
        List<String> required = requiresImage ? List.of("prompt", "image") : List.of("prompt");
        return Optional.of(
                FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(
                                Schema.builder()
                                        .type("OBJECT")
                                        .properties(properties)
                                        .required(required)
                                        .build())
                        .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
        return Single.fromCallable(() -> generate(args, context));
    }

    private Map<String, Object> generate(Map<String, Object> args, ToolContext context) {
        String prompt = text(args.get("prompt"));
        String image = text(args.get("image"));
        if (prompt.isBlank()) return Map.of("error", "prompt must be set");
        if (requiresImage && image.isBlank()) return Map.of("error", "image must be set");
        GenerateImagesRequest request = new GenerateImagesRequest();
        request.setModel(model);
        request.setPrompt(prompt);
        if (!image.isBlank()) request.setImage(image);
        if (!text(args.get("size")).isBlank()) request.setSize(text(args.get("size")));
        request.setResponseFormat(
                text(args.get("response_format")).isBlank()
                        ? (context == null ? "url" : "b64_json")
                        : text(args.get("response_format")));
        if (args.get("watermark") instanceof Boolean value) request.setWatermark(value);
        if (args.get("seed") instanceof Number value) request.setSeed(value.intValue());
        if (args.get("guidance_scale") instanceof Number value)
            request.setGuidanceScale(value.doubleValue());

        ImagesResponse response = service().generateImages(request);
        if (response == null || response.getData() == null || response.getData().isEmpty())
            return Map.of("error", "Ark returned no images");
        List<Map<String, Object>> images = new ArrayList<>();
        for (int index = 0; index < response.getData().size(); index++) {
            ImagesResponse.Image generated = response.getData().get(index);
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            if (generated.getUrl() != null && !generated.getUrl().isBlank()) {
                value.put("url", generated.getUrl());
                if (context != null)
                    context.state().put(name() + "_" + index + "_url", generated.getUrl());
            } else if (generated.getB64Json() != null && !generated.getB64Json().isBlank()) {
                byte[] bytes = Base64.getDecoder().decode(generated.getB64Json());
                String filename = name() + "_" + index + ".png";
                if (context != null) {
                    context.saveArtifact(filename, Part.fromBytes(bytes, "image/png"))
                            .blockingAwait();
                    value.put("artifact", filename);
                } else {
                    value.put("b64_json", generated.getB64Json());
                }
            }
            if (generated.getSize() != null) value.put("size", generated.getSize());
            images.add(value);
        }
        return Map.of(
                "status",
                "success",
                "model",
                response.getModel() == null ? model : response.getModel(),
                "images",
                images);
    }

    @Override
    public void close() {
        if (ownsService && service != null) service.shutdownExecutor();
    }

    private synchronized ArkService service() {
        if (service == null) service = ArkMediaServiceFactory.create(keyEnv, baseEnv);
        return service;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
