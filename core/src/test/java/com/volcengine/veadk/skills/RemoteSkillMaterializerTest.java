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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteSkillMaterializerTest {

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
    void materializeDownloadsExtractsAndCachesSkill(@TempDir Path tempDir) throws Exception {
        AgentKitSkillClient client = mock(AgentKitSkillClient.class);
        doAnswer(
                        invocation -> {
                            Path zipPath = invocation.getArgument(1);
                            writeSkillZip(
                                    zipPath, "nested/alpha-skill", "alpha-skill", "Alpha body.");
                            return null;
                        })
                .when(client)
                .downloadSkill(eq(ALPHA), org.mockito.ArgumentMatchers.any(Path.class));

        RemoteSkillMaterializer materializer = new RemoteSkillMaterializer(client, tempDir);

        Path first = materializer.materialize(ALPHA);
        Path second = materializer.materialize(ALPHA);

        assertThat(first).isEqualTo(second);
        assertThat(first.getFileName().toString()).isEqualTo("alpha-skill");
        assertThat(Files.readString(first.resolve("SKILL.md"))).contains("Alpha body.");
        verify(client, times(1))
                .downloadSkill(eq(ALPHA), org.mockito.ArgumentMatchers.any(Path.class));
    }

    @Test
    void unsafeZipPathFails(@TempDir Path tempDir) throws Exception {
        AgentKitSkillClient client = mock(AgentKitSkillClient.class);
        doAnswer(
                        invocation -> {
                            Path zipPath = invocation.getArgument(1);
                            try (ZipOutputStream output =
                                    new ZipOutputStream(Files.newOutputStream(zipPath))) {
                                output.putNextEntry(new ZipEntry("../evil.txt"));
                                output.write("bad".getBytes(StandardCharsets.UTF_8));
                                output.closeEntry();
                            }
                            return null;
                        })
                .when(client)
                .downloadSkill(eq(ALPHA), org.mockito.ArgumentMatchers.any(Path.class));

        RemoteSkillMaterializer materializer = new RemoteSkillMaterializer(client, tempDir);

        assertThatThrownBy(() -> materializer.materialize(ALPHA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to materialize remote skill alpha-skill")
                .hasMessageContaining("sourceId='ss-test'")
                .hasMessageContaining("cache directory")
                .hasMessageContaining("SKILL.md frontmatter")
                .hasRootCauseMessage("Unsafe path detected in zip archive: ../evil.txt");
    }

    private static void writeSkillZip(Path zipPath, String root, String name, String body)
            throws Exception {
        Files.createDirectories(zipPath.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            output.putNextEntry(new ZipEntry(root + "/SKILL.md"));
            output.write(
                    ("---\nname: " + name + "\ndescription: Test skill.\n---\n" + body + "\n")
                            .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry(root + "/references/policy.md"));
            output.write("Policy text.".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
}
