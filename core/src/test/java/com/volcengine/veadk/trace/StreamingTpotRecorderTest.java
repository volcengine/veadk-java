/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.telemetry.Tracing;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StreamingTpotRecorderTest {
    private static final AttributeKey<Double> KEY =
            AttributeKey.doubleKey(StreamingTpotRecorder.TPOT);

    @AfterEach
    void resetGlobal() {
        GlobalOpenTelemetry.resetForTest();
        Tracing.setTracerForTesting(GlobalOpenTelemetry.getOrNoop().getTracer("veadk"));
    }

    @Test
    void spanAndHistogramAgreeAndParentNeverGetsTpot() {
        try (Telemetry telemetry = new Telemetry(Sampler.alwaysOn())) {
            Span parent = telemetry.tracer().spanBuilder("call_llm").startSpan();
            StreamingTpotRecorder recorder =
                    telemetry.recorder("actual-model", Context.root().with(parent));
            recorder.finish(0.01);
            recorder.finish(0.01);
            parent.end();
            SpanData model =
                    telemetry.spans.stream()
                            .filter(s -> s.getName().equals("model_request"))
                            .findFirst()
                            .orElseThrow();
            assertThat(model.getAttributes().get(KEY)).isEqualTo(0.01);
            assertThat(model.getKind()).isEqualTo(io.opentelemetry.api.trace.SpanKind.CLIENT);
            assertThat(model.getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
            assertThat(
                            telemetry.spans.stream()
                                    .filter(s -> s.getName().equals("call_llm"))
                                    .findFirst()
                                    .orElseThrow()
                                    .getAttributes()
                                    .get(KEY))
                    .isNull();
            var metric = telemetry.metric();
            assertThat(metric.getUnit()).isEqualTo("s");
            var point = metric.getHistogramData().getPoints().iterator().next();
            assertThat(point.getCount()).isEqualTo(1);
            assertThat(point.getSum()).isEqualTo(model.getAttributes().get(KEY));
            assertThat(point.getAttributes().size()).isEqualTo(2);
            assertThat(point.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")))
                    .isEqualTo("actual-model");
            assertThat(point.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")))
                    .isEqualTo("chat");
        }
    }

    @Test
    void metricsRecordWhenTraceSamplingIsOff() {
        try (Telemetry telemetry = new Telemetry(Sampler.alwaysOff())) {
            telemetry.recorder("actual-model", Context.root()).finish(0.01);
            assertThat(telemetry.spans).isEmpty();
            var point = telemetry.metric().getHistogramData().getPoints().iterator().next();
            assertThat(point.getCount()).isEqualTo(1);
            assertThat(point.getSum()).isEqualTo(0.01);
        }
    }

    @Test
    void invalidOrOmittedValuesNeverBecomeZeroSamples() {
        try (Telemetry telemetry = new Telemetry(Sampler.alwaysOn())) {
            for (Double value : new Double[] {null, Double.NaN, Double.POSITIVE_INFINITY, -1.0}) {
                telemetry.recorder("actual-model", Context.root()).finish(value);
            }
            assertThat(telemetry.reader.registration.collectAllMetrics()).isEmpty();
            assertThat(telemetry.spans)
                    .allSatisfy(s -> assertThat(s.getAttributes().get(KEY)).isNull());
        }
    }

    @Test
    void concurrentRequestsAndRetryAttemptsHaveIndependentSpansAndExactlyOneSample() {
        try (Telemetry telemetry = new Telemetry(Sampler.alwaysOn())) {
            StreamingTpotRecorder a = telemetry.recorder("actual-model", Context.root());
            StreamingTpotRecorder b = telemetry.recorder("actual-model", Context.root());
            StreamingTpotRecorder failedAttempt =
                    telemetry.recorder("actual-model", Context.root());
            failedAttempt.finish(null);
            CompletableFuture.allOf(
                            CompletableFuture.runAsync(
                                    () -> {
                                        a.finish(0.01);
                                        a.finish(0.02);
                                    }),
                            CompletableFuture.runAsync(
                                    () -> {
                                        b.finish(0.02);
                                        b.finish(0.01);
                                    }))
                    .join();
            assertThat(telemetry.spans).hasSize(3);
            assertThat(telemetry.spans.stream().map(SpanData::getSpanId).distinct().count())
                    .isEqualTo(3);
            assertThat(
                            telemetry.spans.stream()
                                    .map(s -> s.getAttributes().get(KEY))
                                    .filter(java.util.Objects::nonNull)
                                    .toList())
                    .containsExactlyInAnyOrder(0.01, 0.02);
            var point = telemetry.metric().getHistogramData().getPoints().iterator().next();
            assertThat(point.getCount()).isEqualTo(2);
            assertThat(point.getSum()).isEqualTo(0.03);
        }
    }

    @Test
    void requestAfterTelemetryInitializationDoesNotUseAnEarlierNoopHistogram() {
        GlobalOpenTelemetry.resetForTest();
        Tracing.setTracerForTesting(GlobalOpenTelemetry.getOrNoop().getTracer("veadk"));
        StreamingTpotRecorder.start("early").finish(0.01);
        try (Telemetry telemetry = new Telemetry(Sampler.alwaysOn())) {
            GlobalOpenTelemetry.set(
                    OpenTelemetrySdk.builder()
                            .setTracerProvider(telemetry.tracerProvider)
                            .setMeterProvider(telemetry.meterProvider)
                            .build());
            Tracing.setTracerForTesting(telemetry.tracer());
            StreamingTpotRecorder.start("late").finish(0.01);
            assertThat(telemetry.metric().getHistogramData().getPoints()).hasSize(1);
        }
    }

    @Test
    void spanAndHistogramFailuresAreIndependentlyContained() {
        Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
        Meter meter = mock(Meter.class, RETURNS_DEEP_STUBS);
        Span span = mock(Span.class);
        DoubleHistogram histogram = mock(DoubleHistogram.class);
        when(tracer.spanBuilder("model_request").setParent(any()).setSpanKind(any()).startSpan())
                .thenReturn(span);
        when(meter.histogramBuilder(StreamingTpotRecorder.TPOT).setUnit("s").build())
                .thenReturn(histogram);
        when(span.isRecording()).thenReturn(true);
        doThrow(new IllegalStateException("fixture span failure"))
                .when(span)
                .setAttribute(eq(StreamingTpotRecorder.TPOT), anyDouble());
        doThrow(new IllegalStateException("fixture end failure")).when(span).end();
        StreamingTpotRecorder recorder =
                new StreamingTpotRecorder("actual-model", tracer, meter, Context.root());
        assertThatCode(() -> recorder.finish(0.01)).doesNotThrowAnyException();
        verify(histogram).record(eq(0.01), any());

        Span goodSpan = mock(Span.class);
        when(goodSpan.isRecording()).thenReturn(true);
        when(tracer.spanBuilder("model_request").setParent(any()).setSpanKind(any()).startSpan())
                .thenReturn(goodSpan);
        doThrow(new IllegalStateException("fixture metric failure"))
                .when(histogram)
                .record(anyDouble(), any());
        StreamingTpotRecorder metricFailure =
                new StreamingTpotRecorder("actual-model", tracer, meter, Context.root());
        assertThatCode(() -> metricFailure.finish(0.02)).doesNotThrowAnyException();
        verify(goodSpan).setAttribute(StreamingTpotRecorder.TPOT, 0.02);
        verify(goodSpan).end();
    }

    @Test
    void instrumentInitializationFailuresDoNotPreventTheOtherSignal() {
        Tracer tracer = mock(Tracer.class);
        when(tracer.spanBuilder(any()))
                .thenThrow(new IllegalStateException("fixture tracer failure"));
        try (Telemetry telemetry = new Telemetry(Sampler.alwaysOn())) {
            new StreamingTpotRecorder(
                            "actual-model",
                            tracer,
                            telemetry.meterProvider.get("test"),
                            Context.root())
                    .finish(0.01);
            assertThat(
                            telemetry
                                    .metric()
                                    .getHistogramData()
                                    .getPoints()
                                    .iterator()
                                    .next()
                                    .getCount())
                    .isEqualTo(1);
            Meter meter = mock(Meter.class);
            when(meter.histogramBuilder(any()))
                    .thenThrow(new IllegalStateException("fixture meter failure"));
            assertThatCode(
                            () ->
                                    new StreamingTpotRecorder(
                                                    "actual-model",
                                                    telemetry.tracer(),
                                                    meter,
                                                    Context.root())
                                            .finish(0.02))
                    .doesNotThrowAnyException();
            assertThat(telemetry.spans).hasSize(1);
            assertThat(telemetry.spans.get(0).getAttributes().get(KEY)).isEqualTo(0.02);
        }
    }

    @Test
    void throwingExporterCannotChangeCompletionOrPreventMetrics() {
        try (Telemetry telemetry = new Telemetry(Sampler.alwaysOn(), true)) {
            var recorder = telemetry.recorder("actual-model", Context.root());
            assertThatCode(() -> recorder.finish(0.01)).doesNotThrowAnyException();
            var point = telemetry.metric().getHistogramData().getPoints().iterator().next();
            assertThat(point.getCount()).isEqualTo(1);
            assertThat(point.getSum()).isEqualTo(0.01);
        }
    }

    private static final class Telemetry implements AutoCloseable {
        final List<SpanData> spans = new CopyOnWriteArrayList<>();
        final Reader reader = new Reader();
        final SdkMeterProvider meterProvider =
                SdkMeterProvider.builder().registerMetricReader(reader).build();
        final SdkTracerProvider tracerProvider;

        Telemetry(Sampler sampler) {
            this(sampler, false);
        }

        Telemetry(Sampler sampler, boolean exporterFailure) {
            tracerProvider =
                    SdkTracerProvider.builder()
                            .setSampler(sampler)
                            .addSpanProcessor(
                                    SimpleSpanProcessor.create(
                                            new SpanExporter() {
                                                @Override
                                                public CompletableResultCode export(
                                                        Collection<SpanData> values) {
                                                    if (exporterFailure)
                                                        throw new IllegalStateException(
                                                                "fixture exporter failure");
                                                    spans.addAll(values);
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
                                            }))
                            .build();
        }

        Tracer tracer() {
            return tracerProvider.get("test");
        }

        StreamingTpotRecorder recorder(String model, Context parent) {
            return new StreamingTpotRecorder(model, tracer(), meterProvider.get("test"), parent);
        }

        MetricData metric() {
            return reader.registration.collectAllMetrics().stream()
                    .filter(m -> m.getName().equals(StreamingTpotRecorder.TPOT))
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public void close() {
            tracerProvider.close();
            meterProvider.close();
        }
    }

    private static final class Reader implements MetricReader {
        CollectionRegistration registration;

        @Override
        public void register(CollectionRegistration registration) {
            this.registration = registration;
        }

        @Override
        public AggregationTemporality getAggregationTemporality(InstrumentType type) {
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
