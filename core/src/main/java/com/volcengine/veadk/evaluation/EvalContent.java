/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.evaluation;

import java.util.List;

/** Content value used by an ADK-compatible evaluation set. */
public record EvalContent(List<EvalPart> parts, String role) {
    public EvalContent {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    public static EvalContent userText(String text) {
        return new EvalContent(List.of(new EvalPart(text)), "user");
    }

    public static EvalContent responseText(String text) {
        return new EvalContent(List.of(new EvalPart(text)), null);
    }

    public String text() {
        return parts.stream().map(EvalPart::text).reduce("", String::concat);
    }
}
