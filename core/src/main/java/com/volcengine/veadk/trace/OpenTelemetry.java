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
package com.volcengine.veadk.trace;

import com.google.adk.telemetry.Tracing;
import com.volcengine.veadk.Version;
import com.volcengine.veadk.trace.exporter.AttributeRewritingSpanExporter;
import com.volcengine.veadk.trace.exporter.ExporterFactory;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class OpenTelemetry {

    public static void initOpenTelemetry(List<ExporterFactory> exporterFactories) {

        if (exporterFactories == null || exporterFactories.isEmpty()) {
            return;
        }

        List<SpanExporter> exporters =
                exporterFactories.stream().map(ExporterFactory::create).toList();
        SpanExporter multiExporter = SpanExporter.composite(exporters);

        SpanExporter rewritingExporter = new AttributeRewritingSpanExporter(multiExporter);

        BatchSpanProcessor batchProcessor =
                BatchSpanProcessor.builder(rewritingExporter) // 重写一次
                        .setMaxQueueSize(2048)
                        .setMaxExportBatchSize(512)
                        .setScheduleDelay(100, TimeUnit.MILLISECONDS)
                        .setExporterTimeout(30, TimeUnit.SECONDS)
                        .build();

        Resource resource =
                exporterFactories.stream()
                        .map(ExporterFactory::resource)
                        .reduce(
                                Resource.getDefault()
                                        .merge(
                                                Resource.create(
                                                        Attributes.of(
                                                                AttributeKey.stringKey(
                                                                        "service.name"),
                                                                "veadk_tracing",
                                                                AttributeKey.stringKey(
                                                                        "service.version"),
                                                                Version.JAVA_VEADK_VERSION))),
                                Resource::merge);

        SdkTracerProvider tracerProvider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(batchProcessor)
                        .setResource(resource)
                        .build();

        List<MetricReader> metricReaders =
                exporterFactories.stream()
                        .map(ExporterFactory::metricReader)
                        .flatMap(java.util.Optional::stream)
                        .toList();
        SdkMeterProvider meterProvider = null;
        OpenTelemetrySdkBuilder sdkBuilder =
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider);
        if (!metricReaders.isEmpty()) {
            SdkMeterProviderBuilder meterProviderBuilder =
                    SdkMeterProvider.builder().setResource(resource);
            metricReaders.forEach(meterProviderBuilder::registerMetricReader);
            meterProvider = meterProviderBuilder.build();
            sdkBuilder.setMeterProvider(meterProvider);
        }

        sdkBuilder.buildAndRegisterGlobal();

        Tracing.setTracerForTesting(GlobalOpenTelemetry.getTracer("veadk"));

        SdkMeterProvider finalMeterProvider = meterProvider;
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    tracerProvider.close();
                                    if (finalMeterProvider != null) finalMeterProvider.close();
                                }));
    }
}
