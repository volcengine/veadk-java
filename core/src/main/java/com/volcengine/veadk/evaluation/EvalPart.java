/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.evaluation;

/** Text part in an ADK-compatible evaluation content value. */
public record EvalPart(String text) {
    public EvalPart {
        text = text == null ? "" : text;
    }
}
