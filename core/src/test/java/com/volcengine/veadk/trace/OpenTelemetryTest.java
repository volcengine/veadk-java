/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.telemetry.Tracing;
import com.volcengine.veadk.trace.exporter.ExporterFactory;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenTelemetryTest {
    @BeforeEach
    void resetGlobal() {
        GlobalOpenTelemetry.resetForTest();
    }

    @AfterEach
    void closeGlobal() {
        GlobalOpenTelemetry.resetForTest();
        Tracing.setTracerForTesting(GlobalOpenTelemetry.getOrNoop().getTracer("veadk"));
    }

    @Test
    void registersMetricReaderAndAppliesExporterResourceToSpans() throws InterruptedException {
        CapturingSpanExporter spanExporter = new CapturingSpanExporter();
        CapturingMetricReader metricReader = new CapturingMetricReader();
        ExporterFactory factory =
                new ExporterFactory() {
                    @Override
                    public SpanExporter create() {
                        return spanExporter;
                    }

                    @Override
                    public Resource resource() {
                        return Resource.create(
                                Attributes.of(
                                        AttributeKey.stringKey("service.name"), "apm-service"));
                    }

                    @Override
                    public Optional<MetricReader> metricReader() {
                        return Optional.of(metricReader);
                    }
                };

        OpenTelemetry.initOpenTelemetry(List.of(factory));
        var span = GlobalOpenTelemetry.getTracer("test").spanBuilder("operation").startSpan();
        span.end();

        assertThat(metricReader.registered).isTrue();
        assertThat(spanExporter.exported.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(spanExporter.spans).hasSize(1);
        assertThat(
                        spanExporter
                                .spans
                                .get(0)
                                .getResource()
                                .getAttribute(AttributeKey.stringKey("service.name")))
                .isEqualTo("apm-service");
    }

    private static final class CapturingSpanExporter implements SpanExporter {
        private final List<SpanData> spans = new ArrayList<>();
        private final CountDownLatch exported = new CountDownLatch(1);

        @Override
        public CompletableResultCode export(Collection<SpanData> values) {
            spans.addAll(values);
            exported.countDown();
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private static final class CapturingMetricReader implements MetricReader {
        private boolean registered;

        @Override
        public void register(CollectionRegistration registration) {
            registered = true;
        }

        @Override
        public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
            return AggregationTemporality.CUMULATIVE;
        }

        @Override
        public CompletableResultCode forceFlush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
