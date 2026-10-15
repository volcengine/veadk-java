/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. Licensed under Apache 2.0. */
package com.volcengine.veadk.artifacts;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.types.Part;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalArtifactServiceTest {
    @TempDir Path directory;

    @Test
    void versionsSurviveServiceRestart() {
        var first = new LocalArtifactService(directory);
        assertThat(
                        first.saveArtifact(
                                        "app",
                                        "user",
                                        "session",
                                        "image.png",
                                        Part.fromBytes(new byte[] {1}, "image/png"))
                                .blockingGet())
                .isZero();
        assertThat(
                        first.saveArtifact(
                                        "app",
                                        "user",
                                        "session",
                                        "image.png",
                                        Part.fromBytes(new byte[] {2}, "image/png"))
                                .blockingGet())
                .isEqualTo(1);

        var restarted = new LocalArtifactService(directory);
        assertThat(restarted.listVersions("app", "user", "session", "image.png").blockingGet())
                .containsExactly(0, 1);
        assertThat(
                        restarted
                                .loadArtifact("app", "user", "session", "image.png", 0)
                                .blockingGet()
                                .inlineData()
                                .orElseThrow()
                                .data()
                                .orElseThrow())
                .containsExactly(1);
        assertThat(
                        restarted
                                .loadArtifact("app", "user", "session", "image.png", null)
                                .blockingGet()
                                .inlineData()
                                .orElseThrow()
                                .data()
                                .orElseThrow())
                .containsExactly(2);
    }

    @Test
    void isolatesSessionsAndTreatsNamesAsOpaqueValues() {
        var service = new LocalArtifactService(directory);
        service.saveArtifact("app", "user", "session-a", "../../image.png", Part.fromText("a"))
                .blockingGet();
        service.saveArtifact("app", "user", "session-b", "image.png", Part.fromText("b"))
                .blockingGet();

        assertThat(service.listArtifactKeys("app", "user", "session-a").blockingGet().filenames())
                .containsExactly("../../image.png");
        assertThat(
                        service.loadArtifact("app", "user", "session-a", "image.png", null)
                                .isEmpty()
                                .blockingGet())
                .isTrue();
        assertThat(
                        service.loadArtifact("app", "user", "session-b", "image.png", null)
                                .blockingGet()
                                .text())
                .contains("b");
    }

    @Test
    void deleteRemovesAllVersions() {
        var service = new LocalArtifactService(directory);
        service.saveArtifact("app", "user", "session", "report.txt", Part.fromText("one"))
                .blockingGet();
        service.saveArtifact("app", "user", "session", "report.txt", Part.fromText("two"))
                .blockingGet();
        service.deleteArtifact("app", "user", "session", "report.txt").blockingAwait();
        assertThat(service.listVersions("app", "user", "session", "report.txt").blockingGet())
                .isEmpty();
    }
}
