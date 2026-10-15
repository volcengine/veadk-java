/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. Licensed under Apache 2.0. */
package com.volcengine.veadk.tools.builtin.media;

/** Edits an existing image with Ark SeedEdit. */
public final class ImageEditTool extends ImageGenerationTool {
    public static final String DEFAULT_MODEL = "doubao-seededit-3-0-i2i-250628";

    public ImageEditTool() {
        super(
                "image_edit",
                "Edit an image according to a text instruction.",
                "MODEL_EDIT_API_KEY",
                "MODEL_EDIT_API_BASE",
                "MODEL_EDIT_NAME",
                DEFAULT_MODEL,
                true);
    }
}
