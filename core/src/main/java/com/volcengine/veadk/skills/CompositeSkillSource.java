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
import com.google.adk.skills.SkillSource;
import com.google.adk.skills.SkillSourceException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Combines multiple ADK {@link SkillSource} instances into one ordered source.
 *
 * <p>When multiple sources expose the same skill name, later sources take precedence. This matches
 * {@link #listFrontmatters()}, where later frontmatters replace earlier entries with the same key.
 */
public final class CompositeSkillSource implements SkillSource {

    private final List<SkillSource> sources;

    public CompositeSkillSource(List<? extends SkillSource> sources) {
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources must be set."));
        if (this.sources.isEmpty()) {
            throw new IllegalArgumentException("sources must not be empty.");
        }
    }

    @Override
    public Single<ImmutableMap<String, Frontmatter>> listFrontmatters() {
        return Flowable.fromIterable(sources)
                .concatMapSingle(SkillSource::listFrontmatters)
                .collect(
                        LinkedHashMap<String, Frontmatter>::new,
                        (merged, frontmatters) -> frontmatters.forEach(merged::put))
                .map(ImmutableMap::copyOf);
    }

    @Override
    public Single<ImmutableList<String>> listResources(String skillName, String resourceDirectory) {
        return firstMatchingSource(
                skillName, source -> source.listResources(skillName, resourceDirectory));
    }

    @Override
    public Single<Frontmatter> loadFrontmatter(String skillName) {
        return firstMatchingSource(skillName, source -> source.loadFrontmatter(skillName));
    }

    @Override
    public Single<String> loadInstructions(String skillName) {
        return firstMatchingSource(skillName, source -> source.loadInstructions(skillName));
    }

    @Override
    public Single<ByteSource> loadResource(String skillName, String resourcePath) {
        return firstMatchingSource(
                skillName, source -> source.loadResource(skillName, resourcePath));
    }

    private <T> Single<T> firstMatchingSource(
            String skillName, Function<SkillSource, Single<T>> loader) {
        return firstMatchingSource(skillName, loader, sources.size() - 1);
    }

    private <T> Single<T> firstMatchingSource(
            String skillName, Function<SkillSource, Single<T>> loader, int index) {
        if (index < 0) {
            return Single.error(
                    new SkillSourceException(
                            "Skill not found in configured sources: " + skillName,
                            SKILL_NOT_FOUND));
        }
        return loader.apply(sources.get(index))
                .onErrorResumeNext(
                        error ->
                                isSkillNotFound(error)
                                        ? firstMatchingSource(skillName, loader, index - 1)
                                        : Single.error(error));
    }

    private static boolean isSkillNotFound(Throwable error) {
        return error instanceof SkillSourceException skillSourceException
                && SKILL_NOT_FOUND.equals(skillSourceException.getErrorCode());
    }
}
