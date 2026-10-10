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

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import com.volcengine.veadk.utils.JSONUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Session-scoped filtering policy for remote Skill Space skills. */
public final class SkillSpacePolicy {

    public static final String ENV_NAME = "SKILL_SPACE_POLICY";
    public static final int MAX_BYTES = 8192;

    private final String mode;
    private final Set<String> ids;

    private SkillSpacePolicy(String mode, Set<String> ids) {
        this.mode = Objects.requireNonNull(mode, "mode must be set.");
        this.ids = Set.copyOf(ids);
    }

    public static Optional<SkillSpacePolicy> fromRaw(String rawValue) {
        if (rawValue == null) {
            return Optional.empty();
        }
        return Optional.of(parse(rawValue));
    }

    public static SkillSpacePolicy parse(String rawValue) {
        if (rawValue.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new SkillSpacePolicyException(
                    ENV_NAME + " must not exceed " + MAX_BYTES + " bytes");
        }

        JsonNode payload;
        try {
            payload = JSONUtil.parseJson(rawValue);
        } catch (IOException e) {
            throw new SkillSpacePolicyException(ENV_NAME + " must be valid JSON", e);
        }

        if (!payload.isObject()) {
            throw new SkillSpacePolicyException(ENV_NAME + " must be a JSON object");
        }
        Set<String> fieldNames = new HashSet<>();
        Iterator<String> iterator = payload.fieldNames();
        while (iterator.hasNext()) {
            fieldNames.add(iterator.next());
        }
        if (!fieldNames.equals(Set.of("mode", "ids"))) {
            throw new SkillSpacePolicyException(ENV_NAME + " supports exactly 'mode' and 'ids'");
        }

        String mode = payload.path("mode").asText();
        if (!"allow".equals(mode) && !"deny".equals(mode)) {
            throw new SkillSpacePolicyException(ENV_NAME + ".mode must be 'allow' or 'deny'");
        }

        JsonNode idsNode = payload.path("ids");
        if (!idsNode.isArray()) {
            throw new SkillSpacePolicyException(ENV_NAME + ".ids must be a list");
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode idNode : idsNode) {
            if (!idNode.isTextual() || idNode.asText().isBlank()) {
                throw new SkillSpacePolicyException(
                        ENV_NAME + ".ids must contain non-empty strings");
            }
            String id = idNode.asText();
            if (!id.equals(id.strip())) {
                throw new SkillSpacePolicyException(
                        ENV_NAME + ".ids must not contain surrounding whitespace");
            }
            ids.add(id);
        }
        return new SkillSpacePolicy(mode, ids);
    }

    public boolean allows(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return false;
        }
        boolean selected = ids.contains(skillId);
        return "allow".equals(mode) ? selected : !selected;
    }

    public String mode() {
        return mode;
    }

    public Set<String> ids() {
        return ids;
    }

    public String toJson() {
        return JSONUtil.toJson(ImmutableMap.of("mode", mode, "ids", new TreeSet<>(ids)));
    }

    public static final class SkillSpacePolicyException extends IllegalArgumentException {
        public SkillSpacePolicyException(String message) {
            super(message);
        }

        public SkillSpacePolicyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
