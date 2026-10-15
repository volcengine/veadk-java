/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. Licensed under Apache 2.0. */
package com.volcengine.veadk.example.basic;

import com.google.adk.tools.BaseTool;
import com.volcengine.veadk.tools.builtin.media.ImageEditTool;
import com.volcengine.veadk.tools.builtin.media.ImageGenerationTool;
import com.volcengine.veadk.tools.builtin.media.VideoGenerationTool;
import com.volcengine.veadk.tools.builtin.media.VideoTaskQueryTool;
import java.util.Map;

/** Live examples for image generation/editing and video task submit/query. */
public final class MediaGenerationExample {
    private MediaGenerationExample() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2)
            throw new IllegalArgumentException(
                    "Usage: image <prompt> | edit <image-url> <prompt> | video <prompt> | query"
                            + " <task-id>");
        String mode = args[0];
        BaseTool tool;
        Map<String, Object> parameters;
        switch (mode) {
            case "image" -> {
                tool = new ImageGenerationTool();
                parameters = Map.of("prompt", args[1]);
            }
            case "edit" -> {
                if (args.length < 3)
                    throw new IllegalArgumentException("edit requires an image URL and prompt");
                tool = new ImageEditTool();
                parameters = Map.of("image", args[1], "prompt", args[2]);
            }
            case "video" -> {
                tool = new VideoGenerationTool();
                parameters = Map.of("prompt", args[1]);
            }
            case "query" -> {
                tool = new VideoTaskQueryTool();
                parameters = Map.of("task_id", args[1]);
            }
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        }
        try {
            System.out.println(tool.runAsync(parameters, null).blockingGet());
        } finally {
            if (tool instanceof AutoCloseable closeable) closeable.close();
        }
    }
}
