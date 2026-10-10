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
package com.volcengine.veadk.skills;

import java.util.ArrayList;
import java.util.List;

final class SkillErrorMessages {

    private static final int MAX_SNIPPET_CHARS = 512;

    private SkillErrorMessages() {}

    static String describe(RemoteSkill skill) {
        List<String> parts = new ArrayList<>();
        parts.add("name=" + quote(skill.name()));
        parts.add("sourceId=" + quote(skill.skillSourceId()));
        skill.sourceType().ifPresent(sourceType -> parts.add("sourceType=" + quote(sourceType)));
        skill.id().ifPresent(id -> parts.add("id=" + quote(id)));
        skill.versionId().ifPresent(version -> parts.add("version=" + quote(version)));
        if (skill.path() != null && !skill.path().isBlank()) {
            parts.add("path=" + quote(skill.path()));
        }
        return String.join(", ", parts);
    }

    static String causeMessage(Throwable error) {
        Throwable cursor = error;
        Throwable last = error;
        while (cursor != null) {
            last = cursor;
            cursor = cursor.getCause();
        }
        String message = last.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getMessage();
        }
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    static String responseSnippet(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (text.length() <= MAX_SNIPPET_CHARS) {
            return text;
        }
        return text.substring(0, MAX_SNIPPET_CHARS) + "...";
    }

    private static String quote(String value) {
        return "'" + value + "'";
    }
}
