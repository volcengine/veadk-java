/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.trace.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class APMPlusExporterTest {
    @Test
    void buildsTraceMetricAndResourceFromExplicitConfiguration() {
        APMPlusConfig config =
                new APMPlusConfig(
                        "http://127.0.0.1:4317",
                        "app-key",
                        "checkout-agent",
                        "cn-beijing",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(10));
        APMPlusExporter exporter =
                new APMPlusExporter(
                        config,
                        region -> {
                            throw new AssertionError("explicit app key must take precedence");
                        });

        SpanExporter spanExporter = exporter.create();
        MetricReader metricReader = exporter.metricReader().orElseThrow();
        try {
            assertThat(exporter.create()).isSameAs(spanExporter);
            assertThat(exporter.metricReader().orElseThrow()).isSameAs(metricReader);
            assertThat(exporter.resource().getAttribute(AttributeKey.stringKey("service.name")))
                    .isEqualTo("checkout-agent");
        } finally {
            spanExporter.shutdown();
            metricReader.shutdown();
        }
    }

    @Test
    void fetchesAppKeyUsingSignedGetAppKeyRequest() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> regionHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    query.set(exchange.getRequestURI().getQuery());
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    regionHeader.set(exchange.getRequestHeaders().getFirst("X-Apmplus-Region"));
                    respond(exchange, "{\"data\":{\"app_key\":\"resolved-key\"}}");
                });
        server.start();
        try {
            HttpAPMPlusAppKeyProvider provider =
                    new HttpAPMPlusAppKeyProvider(
                            HttpClient.newHttpClient(),
                            new ObjectMapper(),
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            "ak",
                            "sk",
                            "session-token",
                            Duration.ofSeconds(2),
                            Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC));

            assertThat(provider.getAppKey("cn-beijing")).isEqualTo("resolved-key");
            assertThat(query.get()).isEqualTo("Action=GetAppKey&Version=2024-07-30");
            assertThat(regionHeader.get()).isEqualTo("cn_beijing");
            assertThat(authorization.get())
                    .startsWith(
                            "HMAC-SHA256 Credential=ak/20261015/cn-beijing/apmplus_server/request");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsHttpAndMalformedGetAppKeyFailuresWithoutLeakingCredentials() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    if (calls.getAndIncrement() == 0) respond(exchange, 403, "denied");
                    else respond(exchange, 200, "{}");
                });
        server.start();
        try {
            HttpAPMPlusAppKeyProvider provider = provider(server);
            assertThatThrownBy(() -> provider.getAppKey("cn-beijing"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("APMPlus GetAppKey request failed with HTTP 403.")
                    .hasMessageNotContaining("sk-value");
            assertThatThrownBy(() -> provider.getAppKey("cn-beijing"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("APMPlus GetAppKey response is missing data.app_key.");
        } finally {
            server.stop(0);
        }
    }

    private static HttpAPMPlusAppKeyProvider provider(HttpServer server) {
        return new HttpAPMPlusAppKeyProvider(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "ak-value",
                "sk-value",
                "session-token",
                Duration.ofSeconds(2),
                Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC));
    }

    private static void respond(HttpExchange exchange, String response) throws IOException {
        respond(exchange, 200, response);
    }

    private static void respond(HttpExchange exchange, int status, String response)
            throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
