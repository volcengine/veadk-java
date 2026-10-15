package com.volcengine.veadk.tools.builtin.tts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextToSpeechToolTest {

    @TempDir Path tempDirectory;

    @Test
    void streamsBase64AudioToPcmFile() throws Exception {
        byte[] first = {1, 2};
        byte[] second = {3, 4, 5};
        String stream =
                "{\"code\":0,\"data\":\""
                        + Base64.getEncoder().encodeToString(first)
                        + "\"}\n{\"code\":0,\"sentence\":{}}\n{\"code\":0,\"data\":\""
                        + Base64.getEncoder().encodeToString(second)
                        + "\"}\n{\"code\":20000000}\n";
        TextToSpeechTool tool =
                new TextToSpeechTool(
                        new TextToSpeechTool.Config("app", "key", "speaker"),
                        (config, payload) ->
                                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                        tempDirectory);

        Map<String, Object> result = tool.runAsync(Map.of("text", "你好，VeADK"), null).blockingGet();

        Path saved = Path.of(String.valueOf(result.get("saved_audio_path")));
        assertTrue(saved.startsWith(tempDirectory));
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, Files.readAllBytes(saved));
        assertEquals(5, result.get("audio_bytes"));
    }

    @Test
    void validatesInputConfigurationAndServiceErrors() {
        TextToSpeechTool missing =
                new TextToSpeechTool(
                        new TextToSpeechTool.Config(null, null, "speaker"),
                        (config, payload) -> new ByteArrayInputStream(new byte[0]),
                        tempDirectory);
        assertTrue(
                missing.runAsync(Map.of("text", "hello"), null).blockingGet().containsKey("error"));
        assertEquals(
                "text must be set",
                missing.runAsync(Map.of("text", " "), null).blockingGet().get("error"));

        TextToSpeechTool failed =
                new TextToSpeechTool(
                        new TextToSpeechTool.Config("app", "key", "speaker"),
                        (config, payload) ->
                                new ByteArrayInputStream(
                                        "{\"code\":300001}\n".getBytes(StandardCharsets.UTF_8)),
                        tempDirectory);
        assertEquals(
                "TTS request failed: service returned code 300001",
                failed.runAsync(Map.of("text", "hello"), null).blockingGet().get("error"));
    }
}
