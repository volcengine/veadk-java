/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.identity;

import java.time.Instant;

/** Identity-issued workload token and its absolute expiration time. */
public record WorkloadToken(String value, Instant expiresAt) {
    public WorkloadToken {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("token must be set.");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt must be set.");
    }
}
