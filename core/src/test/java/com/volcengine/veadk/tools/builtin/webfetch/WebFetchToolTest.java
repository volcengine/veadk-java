package com.volcengine.veadk.tools.builtin.webfetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.FunctionDeclaration;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class WebFetchToolTest {

    @Test
    void declarationPublishesPythonCompatibleArguments() {
        FunctionDeclaration declaration = new WebFetchTool().declaration().orElseThrow();

        assertEquals("web_fetch", declaration.name().orElseThrow());
        var parameters = declaration.parameters().orElseThrow();
        assertEquals(List.of("url"), parameters.required().orElseThrow());
        assertEquals(
                List.of("extract_mode", "max_chars", "url"),
                parameters.properties().orElseThrow().keySet().stream().sorted().toList());
    }

    @Test
    void invalidAndPrivateUrlsFailClosedWithoutCallingNetwork() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WebFetchTool.WebFetcher.Transport transport =
                (uri, timeout) -> {
                    calls.incrementAndGet();
                    return response(200, "text/plain", "unexpected");
                };
        WebFetchTool.WebFetcher fetcher =
                new WebFetchTool.WebFetcher(
                        transport,
                        host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")},
                        () -> 1L);

        assertTrue(fetcher.fetch("not-a-url", "markdown", 100).containsKey("error"));
        assertEquals(
                "blocked non-public address",
                fetcher.fetch("http://internal.example/", "markdown", 100).get("error"));
        assertEquals(0, calls.get());
    }

    @Test
    void extractsMarkdownTitleLinksListsAndBoundsContent() throws Exception {
        QueueTransport transport =
                new QueueTransport(
                        response(
                                200,
                                "text/html; charset=utf-8",
                                "<html><head><title>A &amp;"
                                    + " B</title><script>hidden()</script></head><body><h1>Heading</h1><p>Read"
                                    + " <a href='https://example.com/a'>this</a>.</p>"
                                    + "<ul><li>One</li><li>Two</li></ul></body></html>"));
        WebFetchTool.WebFetcher fetcher = fetcher(transport);

        Map<String, Object> result = fetcher.fetch("https://example.com/page", "markdown", 42);

        assertEquals("A & B", result.get("title"));
        assertEquals("https://example.com/page", result.get("url"));
        assertTrue(String.valueOf(result.get("content")).startsWith("# Heading"));
        assertTrue(String.valueOf(result.get("content")).contains("[this](https://"));
        assertFalse(String.valueOf(result.get("content")).contains("hidden"));
        assertEquals(true, result.get("truncated"));
    }

    @Test
    void textModeRemovesMarkdownDecoration() throws Exception {
        QueueTransport transport =
                new QueueTransport(
                        response(
                                200,
                                "text/html",
                                "<h2>Heading</h2><p><a"
                                    + " href='https://example.com'>Label</a></p><li>Item</li>"));

        Map<String, Object> result =
                fetcher(transport).fetch("https://example.com/", "text", 1_000);

        assertEquals("Heading\nLabel\nItem", result.get("content"));
        assertEquals(false, result.get("truncated"));
    }

    @Test
    void redirectTargetIsResolvedAndBlockedBeforeSecondRequest() throws Exception {
        QueueTransport transport =
                new QueueTransport(
                        new WebFetchTool.WebFetcher.Response(
                                302,
                                Map.of("Location", List.of("http://internal.example/secret")),
                                new ByteArrayInputStream(new byte[0])));
        WebFetchTool.WebFetcher fetcher =
                new WebFetchTool.WebFetcher(
                        transport,
                        host ->
                                new InetAddress[] {
                                    InetAddress.getByName(
                                            host.equals("internal.example")
                                                    ? "10.0.0.8"
                                                    : "93.184.216.34")
                                },
                        () -> 1L);

        Map<String, Object> result = fetcher.fetch("https://example.com/start", "markdown", 1_000);

        assertEquals("blocked non-public address", result.get("error"));
        assertEquals(1, transport.calls.get());
    }

    @Test
    void followsMetaRefreshWithinSharedRedirectBudget() throws Exception {
        QueueTransport transport =
                new QueueTransport(
                        response(
                                200,
                                "text/html",
                                "<meta http-equiv='refresh' content='0;url=/final'>"),
                        response(200, "text/plain", "final content"));

        Map<String, Object> result =
                fetcher(transport).fetch("https://example.com/start", "markdown", 1_000);

        assertEquals("https://example.com/final", result.get("url"));
        assertEquals("final content", result.get("content"));
        assertEquals(2, transport.calls.get());
    }

    @Test
    void successfulResponsesAreCachedByUrlModeAndLimit() throws Exception {
        QueueTransport transport = new QueueTransport(response(200, "text/plain", "cached"));
        WebFetchTool.WebFetcher fetcher = fetcher(transport);

        assertEquals(
                "cached", fetcher.fetch("https://example.com/", "markdown", 10).get("content"));
        assertEquals(
                "cached", fetcher.fetch("https://example.com/", "markdown", 10).get("content"));
        assertEquals(1, transport.calls.get());
    }

    @Test
    void extractsPdfTextUsingExistingDocumentParser() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("VeADK PDF content");
                content.endText();
            }
            document.save(output);
            pdf = output.toByteArray();
        }
        QueueTransport transport =
                new QueueTransport(
                        new WebFetchTool.WebFetcher.Response(
                                200,
                                Map.of("Content-Type", List.of("application/pdf")),
                                new ByteArrayInputStream(pdf)));

        Map<String, Object> result =
                fetcher(transport).fetch("https://example.com/document", "text", 1_000);

        assertTrue(String.valueOf(result.get("content")).contains("VeADK PDF content"));
        assertEquals(false, result.get("truncated"));
    }

    @Test
    void httpAndTransportFailuresBecomeErrorResults() throws Exception {
        QueueTransport httpFailure = new QueueTransport(response(503, "text/plain", "unavailable"));
        assertEquals(
                "HTTP 503",
                fetcher(httpFailure).fetch("https://example.com/", "markdown", 100).get("error"));

        WebFetchTool.WebFetcher.Transport ioFailure =
                (uri, timeout) -> {
                    throw new java.io.IOException("connection unavailable");
                };
        WebFetchTool.WebFetcher fetcher =
                new WebFetchTool.WebFetcher(
                        ioFailure,
                        host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")},
                        System::nanoTime);
        assertEquals(
                "request failed: connection unavailable",
                fetcher.fetch("https://example.com/", "markdown", 100).get("error"));
    }

    @Test
    void runAsyncAppliesDefaultsAndClampsMaxChars() throws Exception {
        QueueTransport transport =
                new QueueTransport(
                        response(200, "text/plain", "abcdef"),
                        response(200, "text/plain", "second"));
        WebFetchTool tool = new WebFetchTool(fetcher(transport));

        Map<String, Object> result =
                tool.runAsync(
                                Map.of(
                                        "url", "https://example.com/",
                                        "extract_mode", "invalid",
                                        "max_chars", 3),
                                null)
                        .blockingGet();

        assertEquals("abc", result.get("content"));
        assertEquals(true, result.get("truncated"));
        assertEquals(
                "second",
                tool.runAsync(
                                Map.of(
                                        "url",
                                        "https://example.com/large",
                                        "max_chars",
                                        Long.MAX_VALUE),
                                null)
                        .blockingGet()
                        .get("content"));
    }

    private static WebFetchTool.WebFetcher fetcher(QueueTransport transport) {
        return new WebFetchTool.WebFetcher(
                transport,
                host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")},
                System::nanoTime);
    }

    private static WebFetchTool.WebFetcher.Response response(
            int status, String contentType, String body) {
        return new WebFetchTool.WebFetcher.Response(
                status,
                Map.of("Content-Type", List.of(contentType)),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class QueueTransport implements WebFetchTool.WebFetcher.Transport {
        private final Queue<WebFetchTool.WebFetcher.Response> responses = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();

        private QueueTransport(WebFetchTool.WebFetcher.Response... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public WebFetchTool.WebFetcher.Response get(URI uri, Duration timeout) {
            calls.incrementAndGet();
            return responses.remove();
        }
    }
}
