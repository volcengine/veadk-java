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

import static com.google.adk.skills.SkillSourceException.SKILL_NOT_FOUND;

import com.google.adk.skills.Frontmatter;
import com.google.adk.skills.LocalSkillSource;
import com.google.adk.skills.SkillSource;
import com.google.adk.skills.SkillSourceException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import io.reactivex.rxjava3.core.Single;
import java.nio.file.Path;
import java.util.Objects;

/** Exposes exactly one local skill directory through ADK's {@link SkillSource} contract. */
public final class SingleSkillDirectorySource implements SkillSource {

    private final String skillName;
    private final LocalSkillSource delegate;

    public SingleSkillDirectorySource(Path skillDirectory) {
        Path directory =
                Objects.requireNonNull(skillDirectory, "skillDirectory must be set.")
                        .normalize()
                        .toAbsolutePath();
        Path fileName = directory.getFileName();
        Path parent = directory.getParent();
        if (fileName == null || parent == null) {
            throw new IllegalArgumentException("skillDirectory must have a parent directory.");
        }
        this.skillName = fileName.toString();
        this.delegate = new LocalSkillSource(parent);
    }

    @Override
    public Single<ImmutableMap<String, Frontmatter>> listFrontmatters() {
        return delegate.loadFrontmatter(skillName)
                .map(frontmatter -> ImmutableMap.of(skillName, frontmatter));
    }

    @Override
    public Single<ImmutableList<String>> listResources(String skillName, String resourceDirectory) {
        return requireSelectedSkill(skillName)
                .flatMap(ignored -> delegate.listResources(skillName, resourceDirectory));
    }

    @Override
    public Single<Frontmatter> loadFrontmatter(String skillName) {
        return requireSelectedSkill(skillName).flatMap(delegate::loadFrontmatter);
    }

    @Override
    public Single<String> loadInstructions(String skillName) {
        return requireSelectedSkill(skillName).flatMap(delegate::loadInstructions);
    }

    @Override
    public Single<ByteSource> loadResource(String skillName, String resourcePath) {
        return requireSelectedSkill(skillName)
                .flatMap(name -> delegate.loadResource(name, resourcePath));
    }

    private Single<String> requireSelectedSkill(String requestedSkillName) {
        if (!skillName.equals(requestedSkillName)) {
            return Single.error(
                    new SkillSourceException(
                            "Skill not found: "
                                    + requestedSkillName
                                    + "; this source exposes only "
                                    + skillName,
                            SKILL_NOT_FOUND));
        }
        return Single.just(skillName);
    }
}
