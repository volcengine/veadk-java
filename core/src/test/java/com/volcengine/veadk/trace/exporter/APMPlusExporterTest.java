package com.volcengine.veadk.trace.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.ClearEnvironmentVariable;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

class APMPlusExporterTest {

    @Test
    @SetEnvironmentVariable(
            key = "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
            value = "http://collector/path/v1/traces")
    @SetEnvironmentVariable(key = "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", value = "http/protobuf")
    @ClearEnvironmentVariable(key = "OBSERVABILITY_OPENTELEMETRY_APMPLUS_ENDPOINT")
    @ClearEnvironmentVariable(key = "OBSERVABILITY_OPENTELEMETRY_APMPLUS_API_KEY")
    void create_shouldPreferRuntimeStandardHttpExporter() {
        SpanExporter exporter = new APMPlusExporter().create();
        try {
            assertThat(exporter).isInstanceOf(OtlpHttpSpanExporter.class);
        } finally {
            exporter.close();
        }
    }
}
