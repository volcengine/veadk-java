/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.identity;

import java.util.List;

/** Permission relationship evaluated by Volcengine Identity. */
public record PermissionRequest(
        String namespace,
        Principal principal,
        Principal operation,
        Principal resource,
        List<Principal> originalCallers) {
    public PermissionRequest {
        originalCallers = originalCallers == null ? List.of() : List.copyOf(originalCallers);
    }

    public record Principal(String type, String id) {}
}
