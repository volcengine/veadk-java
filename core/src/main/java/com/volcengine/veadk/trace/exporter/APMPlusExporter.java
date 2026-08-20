/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.volcengine.veadk.trace.exporter;

import com.volcengine.veadk.utils.EnvUtil;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.apache.commons.lang3.StringUtils;

/** Exports Agent traces to the APMPlus OTLP endpoint injected by AgentKit Runtime. */
public class APMPlusExporter implements ExporterFactory {

    @Override
    public SpanExporter create() {
        String standardEndpoint = EnvUtil.getOpenTelemetryTracesEndpoint();
        if (StringUtils.isNotBlank(standardEndpoint)) {
            String protocol = EnvUtil.getOpenTelemetryTracesProtocol();
            if (StringUtils.isBlank(protocol) || "http/protobuf".equalsIgnoreCase(protocol)) {
                return OtlpHttpSpanExporter.builder().setEndpoint(standardEndpoint).build();
            }
            if ("grpc".equalsIgnoreCase(protocol)) {
                return OtlpGrpcSpanExporter.builder().setEndpoint(standardEndpoint).build();
            }
            throw new IllegalStateException(
                    "Unsupported OTEL_EXPORTER_OTLP_TRACES_PROTOCOL: " + protocol);
        }
        return OtlpGrpcSpanExporter.builder()
                .setEndpoint(EnvUtil.getAPMPlusEndpoint())
                .addHeader("x-byteapm-appkey", EnvUtil.getAPMPlusApiKey())
                .build();
    }
}
