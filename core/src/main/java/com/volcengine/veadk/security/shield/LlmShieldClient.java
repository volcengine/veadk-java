/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.shield;

import java.util.Optional;

/** Content moderation client contract. */
public interface LlmShieldClient {
    Optional<String> moderate(
            String message, String role, String hookName, String sessionId, String invocationId)
            throws Exception;
}
