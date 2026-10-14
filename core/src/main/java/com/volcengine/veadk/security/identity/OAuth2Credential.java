/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.identity;

/** OAuth2 token or interactive authorization URL returned by Identity. */
public record OAuth2Credential(Type type, String value) {
    public enum Type {
        TOKEN,
        AUTHORIZATION_URL
    }

    public OAuth2Credential {
        if (type == null) throw new IllegalArgumentException("type must be set.");
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("value must be set.");
    }
}
