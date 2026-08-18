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
package com.volcengine.veadk.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class ConfigLoader {

    private ConfigLoader() {}

    static Map<String, String> load(Path workingDirectory, Map<String, String> environment) {
        Path directory = workingDirectory.toAbsolutePath().normalize();
        Map<String, String> values = new LinkedHashMap<>();

        findUpwards(directory, "config.yaml").ifPresent(path -> values.putAll(loadYaml(path)));
        Path dotenv = directory.resolve(".env");
        if (Files.isRegularFile(dotenv)) {
            values.putAll(loadDotenv(dotenv));
        }
        environment.forEach((key, value) -> values.put(normalize(key), value));
        applyProviderAliases(values);
        return values;
    }

    private static java.util.Optional<Path> findUpwards(Path directory, String filename) {
        Path current = directory;
        while (current != null) {
            Path candidate = current.resolve(filename);
            if (Files.isRegularFile(candidate)) {
                return java.util.Optional.of(candidate);
            }
            current = current.getParent();
        }
        return java.util.Optional.empty();
    }

    private static Map<String, String> loadYaml(Path path) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Object root = yaml.load(reader);
            Map<String, String> flattened = new LinkedHashMap<>();
            if (root instanceof Map<?, ?> map) {
                flatten(map, "", flattened);
            }
            return flattened;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Unable to load VeADK config: " + path, exception);
        }
    }

    private static void flatten(Map<?, ?> source, String prefix, Map<String, String> target) {
        source.forEach(
                (rawKey, value) -> {
                    String key = String.valueOf(rawKey);
                    String path = prefix.isEmpty() ? key : prefix + "_" + key;
                    if (value instanceof Map<?, ?> nested) {
                        flatten(nested, path, target);
                    } else {
                        target.put(normalize(path), stringify(value));
                    }
                });
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
        }
        return String.valueOf(value);
    }

    private static Map<String, String> loadDotenv(Path path) {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring("export ".length()).trim();
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = normalize(line.substring(0, separator).trim());
                String value = unquote(line.substring(separator + 1).trim());
                values.put(key, value);
            }
            return values;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to load VeADK dotenv: " + path, exception);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static void applyProviderAliases(Map<String, String> values) {
        if (!"byteplus".equalsIgnoreCase(values.getOrDefault("CLOUD_PROVIDER", ""))) {
            return;
        }
        copyIfAbsent(values, "BYTEPLUS_ACCESS_KEY", "VOLCENGINE_ACCESS_KEY");
        copyIfAbsent(values, "BYTEPLUS_SECRET_KEY", "VOLCENGINE_SECRET_KEY");
    }

    private static void copyIfAbsent(
            Map<String, String> values, String source, String destination) {
        String existing = values.get(destination);
        String alias = values.get(source);
        if ((existing == null || existing.isBlank()) && alias != null && !alias.isBlank()) {
            values.put(destination, alias);
        }
    }

    static String normalize(String key) {
        return key.trim().toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
