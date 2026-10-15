/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.trace.exporter;

import java.time.Duration;

/** Configuration for exporting traces and metrics to APMPlus. */
public record APMPlusConfig(
        String endpoint,
        String appKey,
        String serviceName,
        String region,
        Duration timeout,
        Duration metricExportInterval) {
    public APMPlusConfig {
        endpoint = requireText(endpoint, "endpoint");
        serviceName = requireText(serviceName, "serviceName");
        region = requireText(region, "region");
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        metricExportInterval =
                metricExportInterval == null ? Duration.ofMinutes(1) : metricExportInterval;
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("timeout must be positive.");
        if (metricExportInterval.isNegative() || metricExportInterval.isZero())
            throw new IllegalArgumentException("metricExportInterval must be positive.");
        appKey = appKey == null ? "" : appKey.trim();
    }

    public static APMPlusConfig fromEnvironment() {
        boolean byteplus = "byteplus".equalsIgnoreCase(env("CLOUD_PROVIDER", ""));
        String region =
                byteplus ? env("BYTEPLUS_REGION", "ap-southeast-1") : env("REGION", "cn-beijing");
        return new APMPlusConfig(
                env(
                        "OBSERVABILITY_OPENTELEMETRY_APMPLUS_ENDPOINT",
                        "http://apmplus-" + region + ".volces.com:4317"),
                env("OBSERVABILITY_OPENTELEMETRY_APMPLUS_API_KEY", ""),
                env("OBSERVABILITY_OPENTELEMETRY_APMPLUS_SERVICE_NAME", "veadk_tracing"),
                region,
                Duration.ofSeconds(30),
                Duration.ofMinutes(1));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " must be set.");
        return value.trim();
    }
}
