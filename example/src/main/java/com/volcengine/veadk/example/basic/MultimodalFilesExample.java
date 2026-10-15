/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. Licensed under Apache 2.0. */
package com.volcengine.veadk.example.basic;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.Runner;
import com.volcengine.veadk.artifacts.LocalArtifactService;
import com.volcengine.veadk.model.ArkLlm;
import com.volcengine.veadk.model.ArkLlmConfig;
import com.volcengine.veadk.multimodal.ArkFileService;
import java.nio.file.Path;
import java.util.List;

/** Live mixed text/media example. Pass an image URL or a local attachment path. */
public final class MultimodalFilesExample {
    private MultimodalFilesExample() {}

    public static void main(String[] args) {
        if (args.length == 0)
            throw new IllegalArgumentException("Pass an image URL or local file path.");
        try (var model =
                        new ArkLlm(
                                ArkLlmConfig.builder()
                                        .modelName(requiredEnv("VEADK_TEST_MODEL"))
                                        .api(ArkLlmConfig.Api.RESPONSES)
                                        .build());
                var files = new ArkFileService()) {
            Part media =
                    args[0].startsWith("http")
                            ? Part.fromUri(args[0], "image/jpeg")
                            : upload(files, Path.of(args[0]));
            var agent = Agent.builder().name("multimodal_assistant").model(model).build();
            var artifacts = new LocalArtifactService(Path.of(".veadk", "artifacts"));
            var runner = new Runner(agent, "multimodal_assistant", artifacts);
            Content prompt =
                    Content.builder()
                            .role("user")
                            .parts(List.of(Part.fromText("Describe this attachment."), media))
                            .build();
            System.out.println(runner.run(prompt));
        }
    }

    private static Part upload(ArkFileService files, Path path) {
        try {
            return files.upload(path);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Attachment upload failed", failure);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
