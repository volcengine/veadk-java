/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 * Licensed under the Apache License, Version 2.0.
 */
package com.volcengine.veadk.multimodal;

import com.google.genai.types.Part;
import com.volcengine.ark.runtime.model.files.FileMeta;
import com.volcengine.ark.runtime.model.files.UploadFileRequest;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.veadk.model.ArkLlmConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Uploads session attachments to Ark Files and returns an ADK file reference. */
public final class ArkFileService implements AutoCloseable {
    public static final long DEFAULT_MAX_BYTES = 20L * 1024 * 1024;
    public static final Set<String> SUPPORTED_MIME_TYPES =
            Set.of(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "image/gif",
                    "image/jpeg",
                    "image/png",
                    "image/webp",
                    "text/markdown",
                    "text/plain",
                    "video/mp4",
                    "video/quicktime",
                    "video/webm");

    private ArkService service;
    private final long maxBytes;
    private final boolean ownsService;

    public ArkFileService() {
        this(null, DEFAULT_MAX_BYTES, true);
    }

    ArkFileService(ArkService service, long maxBytes) {
        this(service, maxBytes, false);
    }

    private ArkFileService(ArkService service, long maxBytes, boolean ownsService) {
        if (!ownsService) this.service = Objects.requireNonNull(service, "service must be set.");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive.");
        this.maxBytes = maxBytes;
        this.ownsService = ownsService;
    }

    public Part upload(Path path) throws IOException {
        String detected = Files.probeContentType(path);
        if (detected == null) {
            throw new IllegalArgumentException(
                    "Unable to detect the attachment MIME type: " + path);
        }
        return upload(path, detected);
    }

    public Part upload(Path path, String mimeType) throws IOException {
        Path file = Objects.requireNonNull(path, "path must be set.").toAbsolutePath().normalize();
        if (!Files.isRegularFile(file))
            throw new IllegalArgumentException("Attachment is not a regular file: " + file);
        String mime =
                Objects.requireNonNull(mimeType, "mimeType must be set.")
                        .trim()
                        .toLowerCase(Locale.ROOT);
        if (!SUPPORTED_MIME_TYPES.contains(mime))
            throw new IllegalArgumentException("Unsupported attachment MIME type: " + mime);
        long size = Files.size(file);
        if (size > maxBytes)
            throw new IllegalArgumentException(
                    "Attachment exceeds the " + maxBytes + " byte limit: " + size);

        UploadFileRequest request = new UploadFileRequest();
        request.setFile(file.toFile());
        request.setPurpose("user_data");
        FileMeta uploaded = service().uploadFile(request);
        if (uploaded == null || uploaded.getId() == null || uploaded.getId().isBlank()) {
            throw new IllegalStateException("Ark Files upload returned no file ID.");
        }
        if ("failed".equalsIgnoreCase(uploaded.getStatus())) {
            throw new IllegalStateException("Ark Files upload failed for " + file.getFileName());
        }
        return Part.fromUri("file_id://" + uploaded.getId(), mime);
    }

    @Override
    public void close() {
        if (ownsService && service != null) service.shutdownExecutor();
    }

    private synchronized ArkService service() {
        if (service == null) {
            String key = System.getenv("MODEL_AGENT_API_KEY");
            if (key == null || key.isBlank())
                throw new IllegalStateException(
                        "Missing required configuration: MODEL_AGENT_API_KEY");
            service =
                    ArkService.builder()
                            .apiKey(key.trim())
                            .baseUrl(apiBase())
                            .timeout(Duration.ofSeconds(60))
                            .build();
        }
        return service;
    }

    private static String apiBase() {
        String value = System.getenv(ArkLlmConfig.MODEL_AGENT_API_BASE);
        return value == null || value.isBlank() ? ArkLlmConfig.DEFAULT_API_BASE : value.trim();
    }
}
