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

import static com.google.adk.skills.SkillSourceException.RESOURCE_LOAD_ERROR;
import static com.google.adk.skills.SkillSourceException.SKILL_LOAD_ERROR;
import static com.google.adk.skills.SkillSourceException.SKILL_NOT_FOUND;

import com.google.adk.skills.Frontmatter;
import com.google.adk.skills.SkillSource;
import com.google.adk.skills.SkillSourceException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import io.reactivex.rxjava3.core.Single;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** ADK Java skill source backed by one remote VeADK Skill Space. */
public final class VeSkillSource implements SkillSource {

    private final String skillSourceId;
    private final AgentKitSkillClient client;
    private final RemoteSkillMaterializer materializer;

    public VeSkillSource(String skillSourceId) {
        this(builder().skillSourceId(skillSourceId));
    }

    private VeSkillSource(Builder builder) {
        this.skillSourceId = requireText(builder.skillSourceId, "skillSourceId must be set.");
        this.client = Objects.requireNonNullElseGet(builder.client, AgentKitSkillClient::new);
        this.materializer =
                Objects.requireNonNullElseGet(
                        builder.materializer,
                        () -> new RemoteSkillMaterializer(this.client, builder.cacheDir));
    }

    public static Builder builder() {
        return new Builder();
    }

    public String skillSourceId() {
        return skillSourceId;
    }

    @Override
    public Single<ImmutableMap<String, Frontmatter>> listFrontmatters() {
        return Single.fromCallable(
                () -> {
                    Map<String, Frontmatter> frontmatters = new LinkedHashMap<>();
                    for (RemoteSkill skill : listRemoteSkills()) {
                        frontmatters.put(skill.name(), toFrontmatter(skill));
                    }
                    return ImmutableMap.copyOf(frontmatters);
                });
    }

    @Override
    public Single<ImmutableList<String>> listResources(String skillName, String resourceDirectory) {
        return loadFromMaterializedSource(
                skillName, source -> source.listResources(skillName, resourceDirectory));
    }

    @Override
    public Single<Frontmatter> loadFrontmatter(String skillName) {
        return loadFromMaterializedSource(skillName, source -> source.loadFrontmatter(skillName));
    }

    @Override
    public Single<String> loadInstructions(String skillName) {
        return loadFromMaterializedSource(skillName, source -> source.loadInstructions(skillName));
    }

    @Override
    public Single<ByteSource> loadResource(String skillName, String resourcePath) {
        return loadFromMaterializedSource(
                skillName, source -> source.loadResource(skillName, resourcePath));
    }

    private <T> Single<T> loadFromMaterializedSource(
            String skillName, Function<SkillSource, Single<T>> loader) {
        return Single.defer(
                () -> {
                    RemoteSkill skill = findRemoteSkill(skillName);
                    Path skillDir = materializer.materialize(skill);
                    return loader.apply(new SingleSkillDirectorySource(skillDir));
                });
    }

    private RemoteSkill findRemoteSkill(String skillName) throws SkillSourceException {
        String requestedSkillName = requireText(skillName, "skillName must be set.");
        return listRemoteSkills().stream()
                .filter(skill -> requestedSkillName.equals(skill.name()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new SkillSourceException(
                                        "Skill '"
                                                + requestedSkillName
                                                + "' not found in '"
                                                + skillSourceId
                                                + "'.",
                                        SKILL_NOT_FOUND));
    }

    private List<RemoteSkill> listRemoteSkills() throws SkillSourceException {
        try {
            return client.listSkills(skillSourceId);
        } catch (UnsupportedOperationException e) {
            throw new SkillSourceException(e.getMessage(), SKILL_LOAD_ERROR, e);
        } catch (Exception e) {
            throw new SkillSourceException(
                    "Failed to list skills from remote skill source '" + skillSourceId + "'.",
                    SKILL_LOAD_ERROR,
                    e);
        }
    }

    private static Frontmatter toFrontmatter(RemoteSkill skill) throws SkillSourceException {
        try {
            return Frontmatter.builder()
                    .name(skill.name())
                    .description(
                            Optional.of(skill.description())
                                    .filter(description -> !description.isBlank())
                                    .orElse("Remote skill " + skill.name()))
                    .build();
        } catch (RuntimeException e) {
            throw new SkillSourceException(
                    "Invalid frontmatter for remote skill '" + skill.name() + "'.",
                    RESOURCE_LOAD_ERROR,
                    e);
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    /** Builder for {@link VeSkillSource}. */
    public static final class Builder {
        private String skillSourceId;
        private Path cacheDir;
        private AgentKitSkillClient client;
        private RemoteSkillMaterializer materializer;

        private Builder() {}

        public Builder skillSourceId(String skillSourceId) {
            this.skillSourceId = skillSourceId;
            return this;
        }

        public Builder cacheDir(Path cacheDir) {
            this.cacheDir = cacheDir;
            return this;
        }

        public Builder client(AgentKitSkillClient client) {
            this.client = client;
            return this;
        }

        public Builder materializer(RemoteSkillMaterializer materializer) {
            this.materializer = materializer;
            return this;
        }

        public VeSkillSource build() {
            return new VeSkillSource(this);
        }
    }
}
