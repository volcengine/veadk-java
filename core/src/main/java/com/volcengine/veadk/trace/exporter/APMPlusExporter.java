/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.trace.exporter;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** OTLP/gRPC trace and metric exporter for APMPlus. */
public final class APMPlusExporter implements ExporterFactory {
    private static final String APP_KEY_HEADER = "x-byteapm-appkey";
    private final APMPlusConfig config;
    private final String appKey;
    private SpanExporter spanExporter;
    private MetricReader metricReader;

    public APMPlusExporter() {
        this(
                APMPlusConfig.fromEnvironment(),
                region -> HttpAPMPlusAppKeyProvider.fromEnvironment().getAppKey(region));
    }

    public APMPlusExporter(APMPlusConfig config) {
        this(config, region -> HttpAPMPlusAppKeyProvider.fromEnvironment().getAppKey(region));
    }

    public APMPlusExporter(APMPlusConfig config, APMPlusAppKeyProvider provider) {
        this.config = Objects.requireNonNull(config);
        Objects.requireNonNull(provider);
        try {
            this.appKey =
                    config.appKey().isBlank()
                            ? requireText(provider.getAppKey(config.region()))
                            : config.appKey();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to resolve APMPlus app key.", e);
        }
    }

    @Override
    public synchronized SpanExporter create() {
        if (spanExporter == null) {
            spanExporter =
                    OtlpGrpcSpanExporter.builder()
                            .setEndpoint(config.endpoint())
                            .addHeader(APP_KEY_HEADER, appKey)
                            .setTimeout(config.timeout())
                            .setConnectTimeout(config.timeout())
                            .build();
        }
        return spanExporter;
    }

    @Override
    public Resource resource() {
        return Resource.create(
                Attributes.of(AttributeKey.stringKey("service.name"), config.serviceName()));
    }

    @Override
    public synchronized Optional<MetricReader> metricReader() {
        if (metricReader == null) {
            OtlpGrpcMetricExporter exporter =
                    OtlpGrpcMetricExporter.builder()
                            .setEndpoint(config.endpoint())
                            .addHeader(APP_KEY_HEADER, appKey)
                            .setTimeout(config.timeout())
                            .setConnectTimeout(config.timeout())
                            .build();
            metricReader =
                    PeriodicMetricReader.builder(exporter)
                            .setInterval(config.metricExportInterval())
                            .build();
        }
        return Optional.of(metricReader);
    }

    /** Flushes any trace and metric pipelines already created for this exporter. */
    public boolean forceFlush() {
        List<CompletableResultCode> flushes = new ArrayList<>(2);
        synchronized (this) {
            if (spanExporter != null) flushes.add(spanExporter.flush());
            if (metricReader != null) flushes.add(metricReader.forceFlush());
        }
        if (flushes.isEmpty()) return true;
        CompletableResultCode result = CompletableResultCode.ofAll(flushes);
        result.join(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
        return result.isSuccess();
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank())
            throw new IllegalStateException("APMPlus app key must be set.");
        return value.trim();
    }
}
