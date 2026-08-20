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

import com.google.adk.Telemetry;
import com.volcengine.veadk.Version;
import com.volcengine.veadk.trace.exporter.AttributeRewritingSpanExporter;
import com.volcengine.veadk.trace.exporter.ExporterFactory;
import com.volcengine.veadk.utils.EnvUtil;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;

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
                        // Keep the HTTP root and its Agent/LLM/tool children in one export batch.
                        // A very short delay lets the platform trace merger finalize a partial
                        // trace before the long-running HTTP root span has ended.
                        .setScheduleDelay(30, TimeUnit.SECONDS)
                        .setExporterTimeout(30, TimeUnit.SECONDS)
                        .build();

        AttributesBuilder resourceAttributes =
                Attributes.builder()
                        .put("service.name", EnvUtil.getOpenTelemetryServiceName())
                        .put("service.version", Version.JAVA_VEADK_VERSION);
        addEnvironmentResourceAttributes(
                resourceAttributes, EnvUtil.getOpenTelemetryResourceAttributes());

        SdkTracerProvider tracerProvider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(batchProcessor)
                        .setResource(
                                Resource.getDefault()
                                        .merge(Resource.create(resourceAttributes.build())))
                        .build();

        OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).buildAndRegisterGlobal();

        Telemetry.setTracerForTesting(GlobalOpenTelemetry.getTracer("veadk"));

        Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close));
    }

    private static void addEnvironmentResourceAttributes(
            AttributesBuilder builder, String configuredAttributes) {
        if (StringUtils.isBlank(configuredAttributes)) {
            return;
        }
        for (String entry : configuredAttributes.split(",")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                continue;
            }
            String key = entry.substring(0, separator).trim();
            String value = entry.substring(separator + 1).trim();
            if (StringUtils.isNotBlank(key) && StringUtils.isNotBlank(value)) {
                builder.put(AttributeKey.stringKey(key.toLowerCase(Locale.ROOT)), value);
            }
        }
    }
}
