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
package com.volcengine.veadk.model;

import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.volcengine.ark.runtime.model.completion.chat.ChatFunctionCall;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.model.completion.chat.ChatToolCall;
import com.volcengine.veadk.utils.JSONUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts one request's history without sharing tool-call state across sessions. */
final class ArkChatHistoryMapper {
    private final Map<String, String> pendingCalls = new LinkedHashMap<>();
    private final Set<String> usedIds = new HashSet<>();
    private int generatedId;

    List<ChatMessage> map(List<Content> contents) {
        for (Content content : contents) {
            for (Part part : content.parts().orElse(List.of())) {
                if (part != null) {
                    part.functionCall().flatMap(FunctionCall::id).ifPresent(usedIds::add);
                }
            }
        }
        List<ChatMessage> messages = new ArrayList<>();
        for (Content content : contents) {
            boolean hasCalls =
                    content.parts().orElse(List.of()).stream()
                            .anyMatch(part -> part != null && part.functionCall().isPresent());
            boolean hasResponses =
                    content.parts().orElse(List.of()).stream()
                            .anyMatch(part -> part != null && part.functionResponse().isPresent());
            if (hasCalls && hasResponses) {
                throw new IllegalArgumentException(
                        "Function calls and responses must be in separate Content turns.");
            }
            List<ChatToolCall> calls = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (Part part : content.parts().orElse(List.of())) {
                if (part == null) continue;
                part.text().ifPresent(text::append);
                part.functionCall().ifPresent(call -> calls.add(mapCall(call)));
                part.functionResponse().ifPresent(response -> messages.add(mapResponse(response)));
            }
            if (!calls.isEmpty() || !text.isEmpty()) {
                ChatMessage message =
                        ChatMessage.builder()
                                .role(
                                        calls.isEmpty()
                                                ? role(content.role().orElse("user"))
                                                : ChatMessageRole.ASSISTANT)
                                .content(text.isEmpty() ? null : text.toString())
                                .build();
                if (!calls.isEmpty()) message.setToolCalls(calls);
                messages.add(message);
            }
        }
        return messages;
    }

    private ChatToolCall mapCall(FunctionCall call) {
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
        return new ChatToolCall(
                id,
                "function",
                new ChatFunctionCall(
                        name, JSONUtil.valueToTree(call.args().orElse(Map.of())).toString()));
    }

    private ChatMessage mapResponse(FunctionResponse response) {
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
        ChatMessage message =
                ChatMessage.builder()
                        .role(ChatMessageRole.TOOL)
                        .content(
                                JSONUtil.valueToTree(response.response().orElse(Map.of()))
                                        .toString())
                        .build();
        message.setToolCallId(id);
        return message;
    }

    private String nextId() {
        String candidate;
        do {
            candidate = "veadk_call_" + generatedId++;
        } while (!usedIds.add(candidate));
        return candidate;
    }

    private static ChatMessageRole role(String role) {
        return switch (role) {
            case "model", "assistant" -> ChatMessageRole.ASSISTANT;
            case "system" -> ChatMessageRole.SYSTEM;
            default -> ChatMessageRole.USER;
        };
    }
}
