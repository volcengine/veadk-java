/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.trace.exporter;

/** Resolves the APMPlus application key used by OTLP exporters. */
@FunctionalInterface
public interface APMPlusAppKeyProvider {
    String getAppKey(String region) throws Exception;
}
