/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.trace;

import com.google.adk.telemetry.Tracing;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

/** Internal request telemetry. Failures must never escape into the model stream. */
public final class StreamingTpotRecorder {
    public static final String TPOT = "gen_ai.chat_completions.streaming_time_per_output_token";
    private final Attributes attributes;
    private Span span = Span.getInvalid();
    private DoubleHistogram histogram;
    private boolean ended;

    public static StreamingTpotRecorder start(String model) {
        Tracer tracer = null;
        Meter meter = null;
        try {
            tracer = Tracing.getTracer();
        } catch (RuntimeException ignored) {
            // Trace and metrics have independent failure boundaries.
        }
        try {
            meter = GlobalOpenTelemetry.getOrNoop().getMeter("veadk");
        } catch (RuntimeException ignored) {
            // Preserve model execution when telemetry is unavailable.
        }
        return new StreamingTpotRecorder(model, tracer, meter, Context.current());
    }

    StreamingTpotRecorder(String model, Tracer tracer, Meter meter, Context parent) {
        attributes =
                Attributes.of(
                        AttributeKey.stringKey("gen_ai.request.model"),
                        model,
                        AttributeKey.stringKey("gen_ai.operation.name"),
                        "chat");
        try {
            if (tracer != null) {
                span =
                        tracer.spanBuilder("model_request")
                                .setParent(parent)
                                .setSpanKind(SpanKind.CLIENT)
                                .startSpan();
                span.setAllAttributes(attributes);
            }
        } catch (RuntimeException ignored) {
            // A partially initialized span is still ended below.
        }
        try {
            if (meter != null) {
                histogram = meter.histogramBuilder(TPOT).setUnit("s").build();
            }
        } catch (RuntimeException ignored) {
            // Never cache a noop instrument before telemetry initialization.
        }
    }

    public synchronized void finish(Double value) {
        if (ended) return;
        ended = true;
        if (value != null && Double.isFinite(value) && value >= 0) {
            try {
                if (span.isRecording()) span.setAttribute(TPOT, value);
            } catch (RuntimeException ignored) {
                // A span failure must not prevent the independent metric attempt.
            }
            try {
                if (histogram != null) histogram.record(value, attributes);
            } catch (RuntimeException ignored) {
                // Do not retry an operation that might already have recorded a sample.
            }
        }
        try {
            span.end();
        } catch (RuntimeException ignored) {
            // Ending telemetry must not change completion or cancellation.
        }
    }
}
