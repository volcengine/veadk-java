package com.volcengine.veadk.trace.exporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AttributeRewritingSpanExporterTest {

    @Test
    void export_rewritesAttributes_correctly() {
        // Prepare input Attributes covering all types and rules
        Attributes input =
                Attributes.builder()
                        .put(AttributeKey.stringKey("gcp.vertex.model"), "m1")
                        .put(AttributeKey.stringKey("gen_ai.system"), "vertex")
                        .put(AttributeKey.booleanKey("normal.bool"), true)
                        .put(AttributeKey.longKey("normal.long"), 42L)
                        .put(AttributeKey.doubleKey("normal.double"), 3.14)
                        .put(AttributeKey.stringArrayKey("normal.sarr"), List.of("a", "b"))
                        .put(AttributeKey.booleanArrayKey("normal.barr"), List.of(true, false))
                        .put(AttributeKey.longArrayKey("normal.larr"), List.of(1L, 2L))
                        .put(AttributeKey.doubleArrayKey("normal.darr"), List.of(1.1, 2.2))
                        .build();

        SpanData inputSpan = Mockito.mock(SpanData.class);
        when(inputSpan.getName()).thenReturn("call_llm");
        when(inputSpan.getAttributes()).thenReturn(input);

        SpanExporter delegate = Mockito.mock(SpanExporter.class);
        when(delegate.export(anyList())).thenReturn(CompletableResultCode.ofSuccess());

        AttributeRewritingSpanExporter exporter = new AttributeRewritingSpanExporter(delegate);
        CompletableResultCode result = exporter.export(Collections.singletonList(inputSpan));
        assertEquals(CompletableResultCode.ofSuccess().isSuccess(), result.isSuccess());

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(delegate).export(captor.capture());
        @SuppressWarnings("unchecked")
        List<SpanData> forwarded = captor.getValue();
        SpanData rewritten = forwarded.get(0);

        Attributes out = rewritten.getAttributes();

        // Prefix removed
        assertEquals("m1", out.get(AttributeKey.stringKey("model")));
        assertNull(out.get(AttributeKey.stringKey("gcp.vertex.model")));

        // gen_ai.system forced to ark
        assertEquals("ark", out.get(AttributeKey.stringKey("gen_ai.system")));

        // Primitive types preserved
        assertEquals(true, out.get(AttributeKey.booleanKey("normal.bool")));
        assertEquals(42L, out.get(AttributeKey.longKey("normal.long")));
        assertEquals(3.14, out.get(AttributeKey.doubleKey("normal.double")));

        // Array types preserved
        assertEquals(List.of("a", "b"), out.get(AttributeKey.stringArrayKey("normal.sarr")));
        assertEquals(List.of(true, false), out.get(AttributeKey.booleanArrayKey("normal.barr")));
        assertEquals(List.of(1L, 2L), out.get(AttributeKey.longArrayKey("normal.larr")));
        assertEquals(List.of(1.1, 2.2), out.get(AttributeKey.doubleArrayKey("normal.darr")));
        assertEquals("chat", out.get(AttributeKey.stringKey("gen_ai.operation.name")));
        assertEquals("llm", out.get(AttributeKey.stringKey("gen_ai.span.kind")));

        // Attribute count reflects rewritten set
        assertEquals(out.size(), rewritten.getTotalAttributeCount());
    }

    @Test
    void export_addsAgentKitPlatformAttributes() {
        Attributes input =
                Attributes.builder()
                        .put("gcp.vertex.agent.tool_call_args", "{\"code\":\"print(1)\"}")
                        .build();
        SpanData inputSpan = Mockito.mock(SpanData.class);
        when(inputSpan.getName()).thenReturn("tool_call [run_code]");
        when(inputSpan.getAttributes()).thenReturn(input);

        SpanExporter delegate = Mockito.mock(SpanExporter.class);
        when(delegate.export(anyList())).thenReturn(CompletableResultCode.ofSuccess());

        new AttributeRewritingSpanExporter(delegate).export(List.of(inputSpan));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(delegate).export(captor.capture());
        SpanData rewritten = (SpanData) captor.getValue().get(0);
        assertEquals(
                "execute_tool",
                rewritten.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
        assertEquals(
                "tool", rewritten.getAttributes().get(AttributeKey.stringKey("gen_ai.span.kind")));
        assertEquals(
                "{\"code\":\"print(1)\"}",
                rewritten.getAttributes().get(AttributeKey.stringKey("gen_ai.input")));
    }

    @Test
    void flush_and_shutdown_delegate() {
        SpanExporter delegate = Mockito.mock(SpanExporter.class);
        when(delegate.flush()).thenReturn(CompletableResultCode.ofSuccess());
        when(delegate.shutdown()).thenReturn(CompletableResultCode.ofSuccess());

        AttributeRewritingSpanExporter exporter = new AttributeRewritingSpanExporter(delegate);
        assertEquals(true, exporter.flush().isSuccess());
        assertEquals(true, exporter.shutdown().isSuccess());
        Mockito.verify(delegate).flush();
        Mockito.verify(delegate).shutdown();
    }
}
