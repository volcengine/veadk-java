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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.skills.SkillSourceException;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VeSkillSourceTest {

    private static final RemoteSkill ALPHA =
            new RemoteSkill(
                    "alpha-skill",
                    "Alpha skill.",
                    "skills/skill-alpha/v1/alpha.zip",
                    "ss-test",
                    "bucket",
                    "skill-alpha",
                    null,
                    "skillspace",
                    "v1");

    @Test
    void listFrontmattersFetchesRemoteMetadata() {
        AgentKitSkillClient client = mock(AgentKitSkillClient.class);
        when(client.listSkills("ss-test"))
                .thenReturn(
                        List.of(
                                ALPHA,
                                new RemoteSkill(
                                        "beta-skill",
                                        "",
                                        "skills/skill-beta/v1/beta.zip",
                                        "ss-test",
                                        "bucket",
                                        "skill-beta",
                                        null,
                                        "skillspace",
                                        "v1")));

        VeSkillSource source =
                VeSkillSource.builder().skillSourceId("ss-test").client(client).build();

        var frontmatters = source.listFrontmatters().blockingGet();

        assertThat(frontmatters.keySet()).containsExactly("alpha-skill", "beta-skill");
        assertThat(frontmatters.get("alpha-skill").description()).isEqualTo("Alpha skill.");
        assertThat(frontmatters.get("beta-skill").description())
                .isEqualTo("Remote skill beta-skill");
    }

    @Test
    void loadInstructionsMaterializesRequestedSkill(@TempDir Path tempDir) throws Exception {
        Path skillDir = writeSkill(tempDir, "alpha-skill", "Alpha body.");
        AgentKitSkillClient client = mock(AgentKitSkillClient.class);
        RemoteSkillMaterializer materializer = mock(RemoteSkillMaterializer.class);
        when(client.listSkills("ss-test")).thenReturn(List.of(ALPHA));
        when(materializer.materialize(ALPHA)).thenReturn(skillDir);

        VeSkillSource source =
                VeSkillSource.builder()
                        .skillSourceId("ss-test")
                        .client(client)
                        .materializer(materializer)
                        .build();

        String instructions = source.loadInstructions("alpha-skill").blockingGet();

        assertThat(instructions).isEqualTo("Alpha body.");
        verify(materializer).materialize(ALPHA);
    }

    @Test
    void loadResourceUsesMaterializedSkillDirectory(@TempDir Path tempDir) throws Exception {
        Path skillDir = writeSkill(tempDir, "alpha-skill", "Alpha body.");
        Path references = skillDir.resolve("references");
        Files.createDirectories(references);
        Files.writeString(references.resolve("policy.md"), "Policy text.", StandardCharsets.UTF_8);

        AgentKitSkillClient client = mock(AgentKitSkillClient.class);
        RemoteSkillMaterializer materializer = mock(RemoteSkillMaterializer.class);
        when(client.listSkills("ss-test")).thenReturn(List.of(ALPHA));
        when(materializer.materialize(ALPHA)).thenReturn(skillDir);

        VeSkillSource source =
                VeSkillSource.builder()
                        .skillSourceId("ss-test")
                        .client(client)
                        .materializer(materializer)
                        .build();

        ImmutableList<String> resources =
                source.listResources("alpha-skill", "references").blockingGet();
        ByteSource resource =
                source.loadResource("alpha-skill", "references/policy.md").blockingGet();

        assertThat(resources).containsExactly("references/policy.md");
        assertThat(resource.asCharSource(StandardCharsets.UTF_8).read()).isEqualTo("Policy text.");
    }

    @Test
    void missingSkillReturnsSkillSourceException() {
        AgentKitSkillClient client = mock(AgentKitSkillClient.class);
        when(client.listSkills("ss-test")).thenReturn(List.of(ALPHA));

        VeSkillSource source =
                VeSkillSource.builder().skillSourceId("ss-test").client(client).build();

        assertThatThrownBy(() -> source.loadInstructions("missing-skill").blockingGet())
                .hasCauseInstanceOf(SkillSourceException.class)
                .hasMessageContaining("Skill 'missing-skill' not found")
                .hasMessageContaining("ss-test")
                .hasMessageContaining("Available skills: [alpha-skill]");
    }

    @Test
    void listFailureIncludesSourceAndPolicyHint() {
        AgentKitSkillClient client = mock(AgentKitSkillClient.class);
        when(client.listSkills("ss-test"))
                .thenThrow(new IllegalStateException("permission denied"));

        VeSkillSource source =
                VeSkillSource.builder().skillSourceId("ss-test").client(client).build();

        assertThatThrownBy(() -> source.listFrontmatters().blockingGet())
                .hasCauseInstanceOf(SkillSourceException.class)
                .hasMessageContaining("ss-test")
                .hasMessageContaining("permission denied")
                .hasMessageContaining("SKILL_SPACE_POLICY");
    }

    private static Path writeSkill(Path parent, String name, String body) throws Exception {
        Path skillDir = parent.resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: Test skill.\n---\n" + body + "\n",
                StandardCharsets.UTF_8);
        return skillDir;
    }
}
