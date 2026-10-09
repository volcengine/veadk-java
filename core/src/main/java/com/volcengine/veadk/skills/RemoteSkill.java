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

import java.util.Objects;
import java.util.Optional;

/** Metadata for a skill stored in a remote VeADK skill source. */
public final class RemoteSkill {

    private final String name;
    private final String description;
    private final String path;
    private final String skillSourceId;
    private final String bucketName;
    private final String id;
    private final String slug;
    private final String sourceType;
    private final String versionId;

    public RemoteSkill(
            String name,
            String description,
            String path,
            String skillSourceId,
            String bucketName,
            String id,
            String slug,
            String sourceType,
            String versionId) {
        this.name = requireText(name, "name must be set.");
        this.description = Objects.requireNonNullElse(description, "");
        this.path = Objects.requireNonNullElse(path, "");
        this.skillSourceId = requireText(skillSourceId, "skillSourceId must be set.");
        this.bucketName = blankToNull(bucketName);
        this.id = blankToNull(id);
        this.slug = blankToNull(slug);
        this.sourceType = blankToNull(sourceType);
        this.versionId = blankToNull(versionId);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String path() {
        return path;
    }

    public String skillSourceId() {
        return skillSourceId;
    }

    public Optional<String> bucketName() {
        return Optional.ofNullable(bucketName);
    }

    public Optional<String> id() {
        return Optional.ofNullable(id);
    }

    public Optional<String> slug() {
        return Optional.ofNullable(slug);
    }

    public Optional<String> sourceType() {
        return Optional.ofNullable(sourceType);
    }

    public Optional<String> versionId() {
        return Optional.ofNullable(versionId);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
