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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class AttributeRewritingSpanExporter implements SpanExporter {

    private static final String PREFIX = "gcp.vertex.";
    private final SpanExporter delegate;

    public AttributeRewritingSpanExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        List<SpanData> rewrittenSpans = new ArrayList<>(spans.size());
        for (SpanData span : spans) {
            rewrittenSpans.add(new RewrittenSpanData(span));
        }
        return delegate.export(rewrittenSpans);
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    private static class RewrittenSpanData implements SpanData {

        private final SpanData delegate;
        private final Attributes rewrittenAttributes;

        RewrittenSpanData(SpanData delegate) {
            this.delegate = delegate;
            this.rewrittenAttributes = rewriteAttributes(delegate.getAttributes());
        }

        private Attributes rewriteAttributes(Attributes attributes) {
            AttributesBuilder builder = Attributes.builder();
            attributes.forEach(
                    (key, value) -> {
                        String newKeyName = key.getKey();

                        if ("gen_ai.system".equals(newKeyName)) {
                            builder.put(AttributeKey.stringKey(newKeyName), "ark");
                            return;
                        }

                        if (newKeyName.startsWith(PREFIX)) {
                            newKeyName = newKeyName.substring(PREFIX.length());
                        }

                        switch (key.getType()) {
                            case STRING:
                                builder.put(AttributeKey.stringKey(newKeyName), (String) value);
                                break;
                            case BOOLEAN:
                                builder.put(AttributeKey.booleanKey(newKeyName), (Boolean) value);
                                break;
                            case LONG:
                                builder.put(AttributeKey.longKey(newKeyName), (Long) value);
                                break;
                            case DOUBLE:
                                builder.put(AttributeKey.doubleKey(newKeyName), (Double) value);
                                break;
                            case STRING_ARRAY:
                                builder.put(
                                        AttributeKey.stringArrayKey(newKeyName),
                                        (List<String>) value);
                                break;
                            case BOOLEAN_ARRAY:
                                builder.put(
                                        AttributeKey.booleanArrayKey(newKeyName),
                                        (List<Boolean>) value);
                                break;
                            case LONG_ARRAY:
                                builder.put(
                                        AttributeKey.longArrayKey(newKeyName), (List<Long>) value);
                                break;
                            case DOUBLE_ARRAY:
                                builder.put(
                                        AttributeKey.doubleArrayKey(newKeyName),
                                        (List<Double>) value);
                                break;
                        }
                    });
            addPlatformAttributes(builder, delegate.getName(), attributes);
            return builder.build();
        }

        private void addPlatformAttributes(
                AttributesBuilder builder, String spanName, Attributes originalAttributes) {
            if (spanName == null) {
                return;
            }
            if (spanName.startsWith("invocation")) {
                builder.put("gen_ai.operation.name", "chain");
                builder.put("gen_ai.span.kind", "workflow");
            } else if (spanName.startsWith("agent_run") || spanName.startsWith("invoke_agent")) {
                builder.put("gen_ai.operation.name", "agent");
                builder.put("gen_ai.span.kind", "agent");
            } else if (spanName.startsWith("call_llm")) {
                builder.put("gen_ai.operation.name", "chat");
                builder.put("gen_ai.span.kind", "llm");
                copyStringAttribute(
                        builder,
                        originalAttributes,
                        "gcp.vertex.agent.llm_request",
                        "gen_ai.input");
                copyStringAttribute(
                        builder,
                        originalAttributes,
                        "gcp.vertex.agent.llm_response",
                        "gen_ai.output");
            } else if (spanName.startsWith("tool_call")) {
                builder.put("gen_ai.operation.name", "execute_tool");
                builder.put("gen_ai.span.kind", "tool");
                copyStringAttribute(
                        builder,
                        originalAttributes,
                        "gcp.vertex.agent.tool_call_args",
                        "gen_ai.input");
            } else if (spanName.startsWith("tool_response")) {
                builder.put("gen_ai.operation.name", "execute_tool");
                builder.put("gen_ai.span.kind", "tool");
                copyStringAttribute(
                        builder,
                        originalAttributes,
                        "gcp.vertex.agent.tool_response",
                        "gen_ai.output");
            }
        }

        private void copyStringAttribute(
                AttributesBuilder builder,
                Attributes originalAttributes,
                String sourceKey,
                String targetKey) {
            String value = originalAttributes.get(AttributeKey.stringKey(sourceKey));
            if (value != null) {
                builder.put(targetKey, value);
            }
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public SpanKind getKind() {
            return delegate.getKind();
        }

        @Override
        public SpanContext getSpanContext() {
            return delegate.getSpanContext();
        }

        @Override
        public SpanContext getParentSpanContext() {
            return delegate.getParentSpanContext();
        }

        @Override
        public StatusData getStatus() {
            return delegate.getStatus();
        }

        @Override
        public long getStartEpochNanos() {
            return delegate.getStartEpochNanos();
        }

        @Override
        public Attributes getAttributes() {
            return rewrittenAttributes;
        }

        @Override
        public List<EventData> getEvents() {
            return delegate.getEvents();
        }

        @Override
        public List<LinkData> getLinks() {
            return delegate.getLinks();
        }

        @Override
        public long getEndEpochNanos() {
            return delegate.getEndEpochNanos();
        }

        @Override
        public boolean hasEnded() {
            return delegate.hasEnded();
        }

        @Override
        public int getTotalRecordedEvents() {
            return delegate.getTotalRecordedEvents();
        }

        @Override
        public int getTotalRecordedLinks() {
            return delegate.getTotalRecordedLinks();
        }

        @Override
        public int getTotalAttributeCount() {
            return rewrittenAttributes.size();
        }

        @Override
        public Resource getResource() {
            return delegate.getResource();
        }

        @Override
        public InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
            return delegate.getInstrumentationLibraryInfo();
        }
    }
}
