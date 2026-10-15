/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.volcengine.veadk.model;

import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.volcengine.ark.runtime.model.responses.content.InputContentItem;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemFile;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemImage;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemText;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemVideo;
import com.volcengine.ark.runtime.model.responses.item.InputItem;
import com.volcengine.ark.runtime.model.responses.item.ItemEasyMessage;
import com.volcengine.ark.runtime.model.responses.item.ItemFunctionToolCall;
import com.volcengine.ark.runtime.model.responses.item.ItemFunctionToolCallOutput;
import com.volcengine.ark.runtime.model.responses.item.MessageContent;
import com.volcengine.veadk.utils.JSONUtil;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Maps ADK content to Responses input items without flattening multimodal message parts. */
final class ArkResponsesInputMapper {
    private static final String FILE_ID_SCHEME = "file_id://";

    private final Map<String, String> pendingCalls = new LinkedHashMap<>();
    private final Set<String> usedIds = new HashSet<>();
    private int generatedId;

    List<InputItem> map(List<Content> contents) {
        contents.forEach(
                content ->
                        content.parts()
                                .orElse(List.of())
                                .forEach(
                                        part -> {
                                            if (part != null) {
                                                part.functionCall()
                                                        .flatMap(FunctionCall::id)
                                                        .ifPresent(usedIds::add);
                                            }
                                        }));
        List<InputItem> result = new ArrayList<>();
        for (Content content : contents) mapContent(content, result);
        return result;
    }

    private void mapContent(Content content, List<InputItem> result) {
        List<Part> parts =
                content.parts().orElse(List.of()).stream()
                        .filter(java.util.Objects::nonNull)
                        .toList();
        boolean hasCalls = parts.stream().anyMatch(part -> part.functionCall().isPresent());
        boolean hasResponses = parts.stream().anyMatch(part -> part.functionResponse().isPresent());
        if (hasCalls && hasResponses) {
            throw new IllegalArgumentException(
                    "Function calls and responses must be in separate Content turns.");
        }
        if (hasResponses) {
            if (parts.stream()
                    .anyMatch(
                            part ->
                                    part.text().isPresent()
                                            || part.inlineData().isPresent()
                                            || part.fileData().isPresent())) {
                throw new IllegalArgumentException(
                        "Function responses cannot be mixed with message content.");
            }
            parts.forEach(
                    part ->
                            part.functionResponse()
                                    .ifPresent(response -> result.add(mapResponse(response))));
            return;
        }

        List<InputContentItem> messageParts = new ArrayList<>();
        for (Part part : parts) {
            part.text()
                    .filter(text -> !text.isEmpty())
                    .ifPresent(text -> messageParts.add(text(text)));
            part.inlineData().ifPresent(blob -> messageParts.add(inline(blob)));
            part.fileData().ifPresent(file -> messageParts.add(file(file)));
        }
        if (!messageParts.isEmpty()) {
            ItemEasyMessage message = new ItemEasyMessage();
            message.setRole(hasCalls ? "assistant" : role(content.role().orElse("user")));
            MessageContent body = new MessageContent();
            body.setListValue(messageParts);
            message.setContent(body);
            result.add(message);
        }
        parts.forEach(part -> part.functionCall().ifPresent(call -> result.add(mapCall(call))));
    }

    private ItemFunctionToolCall mapCall(FunctionCall call) {
        String name =
                call.name()
                        .filter(value -> !value.isBlank())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "A function call must have a name."));
        String id = call.id().filter(value -> !value.isBlank()).orElseGet(this::nextId);
        if (pendingCalls.putIfAbsent(id, name) != null) {
            throw new IllegalArgumentException("Duplicate pending function call ID: " + id);
        }
        ItemFunctionToolCall item = new ItemFunctionToolCall();
        item.setName(name);
        item.setCallId(id);
        item.setArguments(JSONUtil.valueToTree(call.args().orElse(Map.of())).toString());
        return item;
    }

    private ItemFunctionToolCallOutput mapResponse(FunctionResponse response) {
        String id =
                response.id()
                        .filter(value -> !value.isBlank())
                        .orElseGet(
                                () -> {
                                    List<String> matches =
                                            pendingCalls.entrySet().stream()
                                                    .filter(
                                                            entry ->
                                                                    response.name()
                                                                            .filter(
                                                                                    entry.getValue()
                                                                                            ::equals)
                                                                            .isPresent())
                                                    .map(Map.Entry::getKey)
                                                    .toList();
                                    if (matches.size() != 1) {
                                        throw new IllegalArgumentException(
                                                "Missing or ambiguous tool call ID in function"
                                                        + " response.");
                                    }
                                    return matches.get(0);
                                });
        String name = pendingCalls.remove(id);
        if (name == null || response.name().filter(value -> !value.equals(name)).isPresent()) {
            throw new IllegalArgumentException(
                    "Function response does not match a pending tool call: " + id);
        }
        ItemFunctionToolCallOutput item = new ItemFunctionToolCallOutput();
        item.setCallId(id);
        item.setOutput(JSONUtil.valueToTree(response.response().orElse(Map.of())).toString());
        return item;
    }

    private static InputContentItem inline(Blob blob) {
        String mime = required(blob.mimeType().orElse(null), "Inline data MIME type must be set.");
        byte[] data =
                blob.data()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Inline data bytes must be set."));
        return media(
                "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data),
                null,
                mime,
                blob.displayName().orElse(null));
    }

    private static InputContentItem file(FileData file) {
        String uri = required(file.fileUri().orElse(null), "File URI must be set.");
        String mime = required(file.mimeType().orElse(null), "File MIME type must be set.");
        String fileId =
                uri.startsWith(FILE_ID_SCHEME)
                        ? required(uri.substring(FILE_ID_SCHEME.length()), "File ID must be set.")
                        : null;
        return media(fileId == null ? uri : null, fileId, mime, file.displayName().orElse(null));
    }

    private static InputContentItem media(String uri, String fileId, String mime, String name) {
        if (mime.toLowerCase(Locale.ROOT).startsWith("image/")) {
            InputContentItemImage item = new InputContentItemImage();
            item.setDetail("auto");
            if (fileId == null) item.setImageUrl(uri);
            else item.setFileId(fileId);
            return item;
        }
        if (mime.toLowerCase(Locale.ROOT).startsWith("video/")) {
            InputContentItemVideo item = new InputContentItemVideo();
            if (fileId == null) item.setVideoUrl(uri);
            else item.setFileId(fileId);
            return item;
        }
        InputContentItemFile item = new InputContentItemFile();
        item.setFileName(name);
        if (fileId != null) item.setFileId(fileId);
        else if (uri.startsWith("data:")) item.setFileData(uri);
        else item.setFileUrl(uri);
        return item;
    }

    private static InputContentItemText text(String value) {
        InputContentItemText item = new InputContentItemText();
        item.setText(value);
        return item;
    }

    private String nextId() {
        String candidate;
        do candidate = "veadk_call_" + generatedId++;
        while (!usedIds.add(candidate));
        return candidate;
    }

    private static String role(String role) {
        return switch (role) {
            case "model", "assistant" -> "assistant";
            case "system" -> "system";
            default -> "user";
        };
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value;
    }
}
