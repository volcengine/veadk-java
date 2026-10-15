/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 * Licensed under the Apache License, Version 2.0.
 */
package com.volcengine.veadk.tools.builtin.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.volcengine.veadk.utils.JSONUtil;
import io.reactivex.rxjava3.core.Single;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Converts text to PCM audio with Volcengine Seed TTS. */
public final class TextToSpeechTool extends BaseTool {

    private static final URI ENDPOINT =
            URI.create("https://openspeech.bytedance.com/api/v3/tts/unidirectional");
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String DEFAULT_SPEAKER = "zh_female_vv_uranus_bigtts";

    private final Config config;
    private final TtsClient client;
    private final Path outputDirectory;

    public TextToSpeechTool() {
        this(Config.fromEnvironment(), new JdkTtsClient(), outputDirectoryFromEnvironment());
    }

    TextToSpeechTool(Config config, TtsClient client, Path outputDirectory) {
        super("text_to_speech", "Convert text into natural-sounding PCM speech audio.");
        this.config = config;
        this.client = client;
        this.outputDirectory = outputDirectory;
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
                                                        "text",
                                                        Schema.builder().type("STRING").build()))
                                        .required("text")
                                        .build())
                        .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.fromCallable(
                () ->
                        synthesize(
                                args.get("text") == null ? "" : String.valueOf(args.get("text")),
                                toolContext == null || toolContext.userId() == null
                                        ? "veadk"
                                        : toolContext.userId()));
    }

    private Map<String, Object> synthesize(String text, String userId) {
        if (text.isBlank()) {
            return Map.of("error", "text must be set");
        }
        if (!config.complete()) {
            return Map.of(
                    "error",
                    "Missing required environment variables: TOOL_VESPEECH_APP_ID,"
                            + " TOOL_VESPEECH_API_KEY");
        }
        try {
            Files.createDirectories(outputDirectory);
            byte[] audio = readAudio(client.synthesize(config, payload(text, userId)));
            if (audio.length == 0) {
                return Map.of("error", "TTS response contained no audio data");
            }
            Path output = Files.createTempFile(outputDirectory, "veadk-tts-", ".pcm");
            Files.write(output, audio);
            return Map.of("saved_audio_path", output.toString(), "audio_bytes", audio.length);
        } catch (IOException failure) {
            return Map.of("error", "TTS request failed: " + safeMessage(failure));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return Map.of("error", "TTS request interrupted");
        } catch (RuntimeException failure) {
            return Map.of("error", "TTS response failed: " + safeMessage(failure));
        }
    }

    private static byte[] readAudio(InputStream input) throws IOException {
        try (input;
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                ByteArrayOutputStream audio = new ByteArrayOutputStream()) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode event = JSONUtil.parseJson(line);
                int code = event.path("code").asInt();
                if (code == 0
                        && event.hasNonNull("data")
                        && !event.path("data").asText().isBlank()) {
                    audio.write(Base64.getDecoder().decode(event.path("data").asText()));
                } else if (code == 20_000_000) {
                    break;
                } else if (code > 0) {
                    throw new IOException("service returned code " + code);
                }
            }
            return audio.toByteArray();
        }
    }

    private static Map<String, Object> payload(String text, String userId) {
        Map<String, Object> audio =
                Map.of(
                        "format",
                        "pcm",
                        "bit_rate",
                        16_000,
                        "sample_rate",
                        24_000,
                        "enable_timestamp",
                        true);
        Map<String, Object> additions =
                Map.of(
                        "explicit_language", "zh",
                        "disable_markdown_filter", true,
                        "enable_timestamp", true);
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("text", text);
        parameters.put("speaker", "");
        parameters.put("audio_params", audio);
        parameters.put("additions", JSONUtil.toJson(additions));
        return Map.of("user", Map.of("uid", userId), "req_params", parameters);
    }

    private static Path outputDirectoryFromEnvironment() {
        String value = System.getenv("TOOL_VESPEECH_AUDIO_OUTPUT_PATH");
        return value == null || value.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"))
                : Path.of(value);
    }

    private static String safeMessage(Exception failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    record Config(String appId, String apiKey, String speaker) {
        private static Config fromEnvironment() {
            return new Config(
                    value("TOOL_VESPEECH_APP_ID"),
                    value("TOOL_VESPEECH_API_KEY"),
                    Optional.ofNullable(value("TOOL_VESPEECH_SPEAKER"))
                            .filter(item -> !item.isBlank())
                            .orElse(DEFAULT_SPEAKER));
        }

        private boolean complete() {
            return appId != null && !appId.isBlank() && apiKey != null && !apiKey.isBlank();
        }

        private static String value(String name) {
            return System.getenv(name);
        }
    }

    interface TtsClient {
        InputStream synthesize(Config config, Map<String, Object> payload)
                throws IOException, InterruptedException;
    }

    private static final class JdkTtsClient implements TtsClient {
        private final HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public InputStream synthesize(Config config, Map<String, Object> originalPayload)
                throws IOException, InterruptedException {
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters =
                    new LinkedHashMap<>((Map<String, Object>) originalPayload.get("req_params"));
            parameters.put("speaker", config.speaker());
            Map<String, Object> payload =
                    Map.of("user", originalPayload.get("user"), "req_params", parameters);
            HttpRequest request =
                    HttpRequest.newBuilder(ENDPOINT)
                            .timeout(TIMEOUT)
                            .header("X-Api-App-Id", config.appId())
                            .header("X-Api-Key", config.apiKey())
                            .header("X-Api-Resource-Id", "seed-tts-2.0")
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            mapper.writeValueAsString(payload)))
                            .build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode());
            }
            return response.body();
        }
    }
}
