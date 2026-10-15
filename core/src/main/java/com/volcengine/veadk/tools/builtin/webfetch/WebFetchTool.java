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
package com.volcengine.veadk.tools.builtin.webfetch;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import io.reactivex.rxjava3.core.Single;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches readable content from public HTTP(S) pages and PDF documents. */
public final class WebFetchTool extends BaseTool {

    static final int DEFAULT_MAX_CHARS = 50_000;
    static final int MAX_CHARS = 200_000;

    private final WebFetcher fetcher;

    public WebFetchTool() {
        this(new WebFetcher());
    }

    WebFetchTool(WebFetcher fetcher) {
        super(
                "web_fetch",
                "Fetch a public HTTP(S) page or PDF and return its readable content without "
                        + "executing JavaScript.");
        this.fetcher = fetcher;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(
                FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(
                                Schema.builder()
                                        .type("OBJECT")
                                        .properties(
                                                ImmutableMap.of(
                                                        "url",
                                                        Schema.builder()
                                                                .type("STRING")
                                                                .description(
                                                                        "The public http(s) URL to"
                                                                                + " fetch.")
                                                                .build(),
                                                        "extract_mode",
                                                        Schema.builder()
                                                                .type("STRING")
                                                                .description(
                                                                        "markdown (default) or"
                                                                                + " text.")
                                                                .build(),
                                                        "max_chars",
                                                        Schema.builder()
                                                                .type("INTEGER")
                                                                .description(
                                                                        "Maximum returned content"
                                                                            + " length; defaults to"
                                                                            + " 50000.")
                                                                .build()))
                                        .required("url")
                                        .build())
                        .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.fromCallable(
                () -> {
                    String url = args.get("url") == null ? "" : String.valueOf(args.get("url"));
                    String mode =
                            args.get("extract_mode") == null
                                    ? "markdown"
                                    : String.valueOf(args.get("extract_mode"));
                    int maxChars = parseMaxChars(args.get("max_chars"));
                    return fetcher.fetch(url, mode, maxChars);
                });
    }

    private static int parseMaxChars(Object value) {
        if (value == null) {
            return DEFAULT_MAX_CHARS;
        }
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            return (int) Math.max(1, Math.min(MAX_CHARS, parsed));
        } catch (NumberFormatException ignored) {
            return DEFAULT_MAX_CHARS;
        }
    }

    static final class WebFetcher {
        private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
        private static final int MAX_REDIRECTS = 3;
        private static final int MAX_HTML_BYTES = 2_000_000;
        private static final int MAX_PDF_BYTES = 10_000_000;
        private static final int MAX_CACHE_ENTRIES = 128;
        private static final long CACHE_TTL_NANOS = Duration.ofMinutes(15).toNanos();
        private static final String USER_AGENT =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

        private static final Pattern TITLE =
                Pattern.compile("<title[^>]*>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE);
        private static final Pattern HEAD =
                Pattern.compile("<head[^>]*>[\\s\\S]*?</head\\s*>", Pattern.CASE_INSENSITIVE);
        private static final Pattern SCRIPT_STYLE =
                Pattern.compile(
                        "<(script|style|noscript)[^>]*>[\\s\\S]*?</\\1\\s*>",
                        Pattern.CASE_INSENSITIVE);
        private static final Pattern LINK =
                Pattern.compile(
                        "<a\\s+[^>]*href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>",
                        Pattern.CASE_INSENSITIVE);
        private static final Pattern HEADING =
                Pattern.compile("<h([1-6])[^>]*>([\\s\\S]*?)</h\\1>", Pattern.CASE_INSENSITIVE);
        private static final Pattern LIST_ITEM =
                Pattern.compile("<li[^>]*>([\\s\\S]*?)</li>", Pattern.CASE_INSENSITIVE);
        private static final Pattern META_REFRESH =
                Pattern.compile(
                        "<meta[^>]+http-equiv=[\"']?refresh[\"']?[^>]*content=[\"']?[^\"'>]*?url=([^\"'>\\s]+)",
                        Pattern.CASE_INSENSITIVE);
        private static final Pattern CHARSET =
                Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);
        private static final Pattern TAG = Pattern.compile("<[^>]+>");

        private final Transport transport;
        private final HostResolver resolver;
        private final LongSupplier nanoTime;
        private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

        WebFetcher() {
            this(new JdkTransport(), InetAddress::getAllByName, System::nanoTime);
        }

        WebFetcher(Transport transport, HostResolver resolver, LongSupplier nanoTime) {
            this.transport = transport;
            this.resolver = resolver;
            this.nanoTime = nanoTime;
        }

        Map<String, Object> fetch(String url, String requestedMode, int requestedMaxChars) {
            String mode = "text".equals(requestedMode) ? "text" : "markdown";
            int maxChars = Math.max(1, Math.min(MAX_CHARS, requestedMaxChars));
            CacheKey key = new CacheKey(url, mode, maxChars);
            CacheEntry cached = cache.get(key);
            if (cached != null && cached.expiresAtNanos() > nanoTime.getAsLong()) {
                return cached.result();
            }

            try {
                Map<String, Object> result = fetchAndExtract(url, mode, maxChars);
                if (cache.size() >= MAX_CACHE_ENTRIES) {
                    cache.clear();
                }
                cache.put(key, new CacheEntry(nanoTime.getAsLong() + CACHE_TTL_NANOS, result));
                return result;
            } catch (WebFetchException failure) {
                return error(failure.getMessage());
            } catch (IOException failure) {
                return error("request failed: " + safeMessage(failure));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return error("request interrupted");
            } catch (RuntimeException failure) {
                return error("content extraction failed: " + safeMessage(failure));
            }
        }

        private Map<String, Object> fetchAndExtract(String url, String mode, int maxChars)
                throws IOException, InterruptedException, WebFetchException {
            URI current = checkedUri(url);
            int redirects = 0;
            while (true) {
                Response response = transport.get(current, REQUEST_TIMEOUT);
                try (InputStream body = response.body()) {
                    String location = response.header("location");
                    if (isRedirect(response.statusCode()) && location != null) {
                        redirects++;
                        if (redirects > MAX_REDIRECTS) {
                            throw new WebFetchException(
                                    "too many redirects (>" + MAX_REDIRECTS + ")");
                        }
                        current = checkedUri(current.resolve(location).toString());
                        continue;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new WebFetchException("HTTP " + response.statusCode());
                    }

                    String contentType =
                            Optional.ofNullable(response.header("content-type")).orElse("");
                    boolean declaredPdf = contentType.toLowerCase(Locale.ROOT).contains("pdf");
                    byte[] raw = readCapped(body, declaredPdf ? MAX_PDF_BYTES : MAX_HTML_BYTES);
                    if (declaredPdf || startsWithPdfMagic(raw)) {
                        return extractPdf(current, raw, maxChars);
                    }

                    String decoded = decode(raw, contentType);
                    boolean html =
                            contentType.toLowerCase(Locale.ROOT).contains("html")
                                    || contentType.toLowerCase(Locale.ROOT).contains("xml");
                    if (html && redirects < MAX_REDIRECTS) {
                        String metaTarget = metaRefresh(decoded);
                        if (metaTarget != null) {
                            URI next = checkedUri(current.resolve(metaTarget).toString());
                            if (!next.equals(current)) {
                                redirects++;
                                current = next;
                                continue;
                            }
                        }
                    }
                    if (!html) {
                        return result(current, null, normalize(decoded), maxChars);
                    }

                    ExtractedHtml extracted = htmlToMarkdown(decoded);
                    String content =
                            "text".equals(mode)
                                    ? markdownToText(extracted.markdown())
                                    : extracted.markdown();
                    if (content.isEmpty()) {
                        content = normalize(stripTags(decoded));
                    }
                    return result(current, extracted.title(), content, maxChars);
                }
            }
        }

        private URI checkedUri(String value) throws WebFetchException {
            final URI uri;
            try {
                uri = new URI(value);
            } catch (URISyntaxException | NullPointerException failure) {
                throw new WebFetchException("invalid URL");
            }
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new WebFetchException("only http(s) URLs are supported");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new WebFetchException("URL has no host");
            }
            final InetAddress[] addresses;
            try {
                addresses = resolver.resolve(uri.getHost());
            } catch (IOException failure) {
                throw new WebFetchException("DNS resolution failed for host");
            }
            if (addresses.length == 0) {
                throw new WebFetchException("DNS resolution returned no addresses");
            }
            for (InetAddress address : addresses) {
                if (!isPublicAddress(address)) {
                    throw new WebFetchException("blocked non-public address");
                }
            }
            return uri;
        }

        private static boolean isPublicAddress(InetAddress address) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                return false;
            }
            byte[] bytes = address.getAddress();
            if (address instanceof Inet4Address) {
                int first = Byte.toUnsignedInt(bytes[0]);
                int second = Byte.toUnsignedInt(bytes[1]);
                int third = Byte.toUnsignedInt(bytes[2]);
                return first != 0
                        && first < 224
                        && !(first == 100 && second >= 64 && second <= 127)
                        && !(first == 192 && second == 0 && third == 0)
                        && !(first == 192 && second == 0 && third == 2)
                        && !(first == 198 && (second == 18 || second == 19))
                        && !(first == 198 && second == 51 && third == 100)
                        && !(first == 203 && second == 0 && third == 113);
            }
            if (address instanceof Inet6Address) {
                int first = Byte.toUnsignedInt(bytes[0]);
                int second = Byte.toUnsignedInt(bytes[1]);
                boolean uniqueLocal = (first & 0xfe) == 0xfc;
                boolean documentation =
                        first == 0x20
                                && second == 0x01
                                && Byte.toUnsignedInt(bytes[2]) == 0x0d
                                && Byte.toUnsignedInt(bytes[3]) == 0xb8;
                return !uniqueLocal && !documentation;
            }
            return false;
        }

        private static Map<String, Object> extractPdf(URI uri, byte[] raw, int maxChars)
                throws WebFetchException {
            try {
                Document document =
                        new ApacheTikaDocumentParser().parse(new ByteArrayInputStream(raw));
                String text = normalize(document.text());
                if (text.isEmpty()) {
                    text = "[PDF had no extractable text]";
                }
                String title =
                        document.metadata().containsKey("title")
                                ? document.metadata().getString("title")
                                : null;
                return result(uri, title, text, maxChars);
            } catch (RuntimeException failure) {
                throw new WebFetchException("PDF text extraction failed");
            }
        }

        private static ExtractedHtml htmlToMarkdown(String html) {
            Matcher titleMatcher = TITLE.matcher(html);
            String title = titleMatcher.find() ? normalize(stripTags(titleMatcher.group(1))) : null;
            String text = HEAD.matcher(html).replaceAll("");
            text = SCRIPT_STYLE.matcher(text).replaceAll("");
            text =
                    replace(
                            text,
                            LINK,
                            match -> {
                                String label = normalize(stripTags(match.group(2)));
                                return label.isEmpty()
                                        ? match.group(1)
                                        : "[" + label + "](" + match.group(1) + ")";
                            });
            text =
                    replace(
                            text,
                            HEADING,
                            match -> {
                                int level = Integer.parseInt(match.group(1));
                                return "\n"
                                        + "#".repeat(level)
                                        + " "
                                        + normalize(stripTags(match.group(2)))
                                        + "\n";
                            });
            text = replace(text, LIST_ITEM, match -> "\n- " + normalize(stripTags(match.group(1))));
            text = text.replaceAll("(?i)<(br|hr)\\s*/?>", "\n");
            text =
                    text.replaceAll(
                            "(?i)</(p|div|section|article|header|footer|table|tr|ul|ol)>", "\n");
            return new ExtractedHtml(normalize(stripTags(text)), title);
        }

        private static String markdownToText(String markdown) {
            String text = markdown.replaceAll("!\\[[^]]*]\\([^)]+\\)", "");
            text = text.replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1");
            text = text.replaceAll("`([^`]+)`", "$1");
            text = text.replaceAll("(?m)^#{1,6}\\s+", "");
            text = text.replaceAll("(?m)^\\s*[-*+]\\s+", "");
            text = text.replaceAll("(?m)^\\s*\\d+\\.\\s+", "");
            return normalize(text);
        }

        private static String metaRefresh(String html) {
            Matcher matcher =
                    META_REFRESH.matcher(html.substring(0, Math.min(4096, html.length())));
            return matcher.find() ? decodeEntities(matcher.group(1)) : null;
        }

        private static String decode(byte[] raw, String contentType) {
            Charset charset = StandardCharsets.UTF_8;
            Matcher matcher = CHARSET.matcher(contentType);
            if (matcher.find()) {
                try {
                    charset = Charset.forName(matcher.group(1));
                } catch (RuntimeException ignored) {
                    charset = StandardCharsets.UTF_8;
                }
            }
            return new String(raw, charset);
        }

        private static byte[] readCapped(InputStream input, int cap) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(cap, 16_384));
            byte[] buffer = new byte[16_384];
            int remaining = cap;
            while (remaining > 0) {
                int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (count < 0) {
                    break;
                }
                output.write(buffer, 0, count);
                remaining -= count;
            }
            return output.toByteArray();
        }

        private static Map<String, Object> result(
                URI uri, String title, String content, int maxChars) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("url", uri.toString());
            result.put("title", title);
            result.put("content", content.substring(0, Math.min(content.length(), maxChars)));
            result.put("truncated", content.length() > maxChars);
            return Collections.unmodifiableMap(result);
        }

        private static Map<String, Object> error(String message) {
            return Collections.singletonMap("error", message);
        }

        private static String safeMessage(Exception failure) {
            return failure.getMessage() == null || failure.getMessage().isBlank()
                    ? failure.getClass().getSimpleName()
                    : failure.getMessage();
        }

        private static boolean isRedirect(int status) {
            return status == 301
                    || status == 302
                    || status == 303
                    || status == 307
                    || status == 308;
        }

        private static boolean startsWithPdfMagic(byte[] raw) {
            return raw.length >= 5
                    && raw[0] == '%'
                    && raw[1] == 'P'
                    && raw[2] == 'D'
                    && raw[3] == 'F'
                    && raw[4] == '-';
        }

        private static String stripTags(String value) {
            return decodeEntities(TAG.matcher(value).replaceAll(""));
        }

        private static String decodeEntities(String value) {
            String decoded =
                    value.replace("&amp;", "&")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&quot;", "\"")
                            .replace("&#39;", "'")
                            .replace("&nbsp;", " ");
            Matcher matcher = Pattern.compile("&#(x?[0-9A-Fa-f]+);").matcher(decoded);
            StringBuffer result = new StringBuffer();
            while (matcher.find()) {
                int radix = matcher.group(1).startsWith("x") ? 16 : 10;
                String digits = radix == 16 ? matcher.group(1).substring(1) : matcher.group(1);
                try {
                    matcher.appendReplacement(
                            result,
                            Matcher.quoteReplacement(
                                    new String(
                                            Character.toChars(Integer.parseInt(digits, radix)))));
                } catch (IllegalArgumentException ignored) {
                    matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
                }
            }
            matcher.appendTail(result);
            return result.toString();
        }

        private static String normalize(String value) {
            return value.replace("\r", "")
                    .replaceAll("[ \\t]+\\n", "\n")
                    .replaceAll("\\n{3,}", "\n\n")
                    .replaceAll("[ \\t]{2,}", " ")
                    .trim();
        }

        private static String replace(String input, Pattern pattern, Replacement replacement) {
            Matcher matcher = pattern.matcher(input);
            StringBuffer output = new StringBuffer();
            while (matcher.find()) {
                matcher.appendReplacement(
                        output, Matcher.quoteReplacement(replacement.apply(matcher)));
            }
            matcher.appendTail(output);
            return output.toString();
        }

        interface Transport {
            Response get(URI uri, Duration timeout) throws IOException, InterruptedException;
        }

        interface HostResolver {
            InetAddress[] resolve(String host) throws IOException;
        }

        record Response(int statusCode, Map<String, List<String>> headers, InputStream body) {
            Response {
                headers = new LinkedHashMap<>(headers);
            }

            String header(String name) {
                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                        return entry.getValue().get(0);
                    }
                }
                return null;
            }
        }

        private record CacheKey(String url, String mode, int maxChars) {}

        private record CacheEntry(long expiresAtNanos, Map<String, Object> result) {}

        private record ExtractedHtml(String markdown, String title) {}

        private interface Replacement {
            String apply(Matcher matcher);
        }

        private static final class JdkTransport implements Transport {
            private final HttpClient client =
                    HttpClient.newBuilder()
                            .connectTimeout(REQUEST_TIMEOUT)
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();

            @Override
            public Response get(URI uri, Duration timeout)
                    throws IOException, InterruptedException {
                HttpRequest request =
                        HttpRequest.newBuilder(uri)
                                .timeout(timeout)
                                .header("User-Agent", USER_AGENT)
                                .header("Accept-Language", "en-US,en;q=0.9")
                                .header(
                                        "Accept",
                                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                                .GET()
                                .build();
                HttpResponse<InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                return new Response(
                        response.statusCode(), response.headers().map(), response.body());
            }
        }

        private static final class WebFetchException extends Exception {
            private WebFetchException(String message) {
                super(message);
            }
        }
    }
}
