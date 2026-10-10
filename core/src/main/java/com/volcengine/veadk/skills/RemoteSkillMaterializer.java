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
package com.volcengine.veadk.skills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.volcengine.veadk.utils.JSONUtil;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads and caches remote skills as local ADK-loadable skill directories. */
public class RemoteSkillMaterializer {

    private static final List<String> SKILL_MD_NAMES = List.of("SKILL.md", "skill.md");
    private static final String STAGING_DIR = "__staging__";
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final AgentKitSkillClient client;
    private final Path cacheDir;

    public RemoteSkillMaterializer(AgentKitSkillClient client, Path cacheDir) {
        this.client = Objects.requireNonNull(client, "client must be set.");
        this.cacheDir =
                Objects.requireNonNullElseGet(cacheDir, RemoteSkillMaterializer::defaultCacheDir)
                        .toAbsolutePath()
                        .normalize();
    }

    public Path materialize(RemoteSkill skill) {
        Objects.requireNonNull(skill, "skill must be set.");
        try {
            Files.createDirectories(cacheDir);
            Path versionDir = versionDir(skill);
            Optional<Path> cached = cachedSkillDir(versionDir);
            if (cached.isPresent()) {
                validateSkillDir(cached.get());
                return cached.get();
            }

            if (Files.exists(versionDir)) {
                deleteRecursively(versionDir);
            }
            Files.createDirectories(versionDir);

            Path zipPath = versionDir.resolve(safeCachePart(skill.name()) + ".zip");
            Path stagingDir = versionDir.resolve(STAGING_DIR);
            if (Files.exists(stagingDir)) {
                deleteRecursively(stagingDir);
            }

            client.downloadSkill(skill, zipPath);
            try {
                safeExtractZip(zipPath, stagingDir);
            } finally {
                Files.deleteIfExists(zipPath);
            }

            Path finalDir = normalizeExtractedSkillDir(stagingDir, versionDir, skill);
            validateSkillDir(finalDir);
            cleanupOldVersions(finalDir.getParent());
            return finalDir;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to materialize remote skill "
                            + skill.name()
                            + " ("
                            + SkillErrorMessages.describe(skill)
                            + ") into cache directory '"
                            + cacheDir
                            + "': "
                            + SkillErrorMessages.causeMessage(e)
                            + ". Check the downloaded archive, SKILL.md frontmatter, zip paths,"
                            + " and cache directory permissions.",
                    e);
        }
    }

    private Path versionDir(RemoteSkill skill) {
        String sourceType =
                skill.sourceType()
                        .orElseGet(
                                () ->
                                        skill.skillSourceId().startsWith("sp-")
                                                ? "skillhub"
                                                : "skillspace");
        return cacheDir.resolve(safeCachePart(sourceType))
                .resolve(safeCachePart(skill.skillSourceId()))
                .resolve(safeCachePart(skill.name()))
                .resolve(safeCachePart(versionKey(skill)));
    }

    private static String versionKey(RemoteSkill skill) {
        return skill.versionId()
                .or(() -> legacyVersionFromPath(skill.path()))
                .orElseGet(
                        () -> "metadata-" + Integer.toHexString(JSONUtil.toJson(skill).hashCode()));
    }

    private static Optional<String> legacyVersionFromPath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        List<String> parts = Stream.of(path.split("/")).filter(part -> !part.isBlank()).toList();
        if (parts.size() >= 3 && "skills".equals(parts.get(0))) {
            return Optional.of(parts.get(2));
        }
        return Optional.empty();
    }

    private static Optional<Path> cachedSkillDir(Path versionDir) throws IOException {
        if (!Files.isDirectory(versionDir)) {
            return Optional.empty();
        }
        try (Stream<Path> children = Files.list(versionDir)) {
            List<Path> candidates =
                    children.filter(Files::isDirectory)
                            .filter(path -> !STAGING_DIR.equals(path.getFileName().toString()))
                            .filter(RemoteSkillMaterializer::hasSkillMd)
                            .toList();
            return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
        }
    }

    private static void safeExtractZip(Path zipPath, Path destination) throws IOException {
        Path destinationRoot = destination.toAbsolutePath().normalize();
        Files.createDirectories(destinationRoot);
        try (InputStream inputStream = Files.newInputStream(zipPath);
                ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String entryName = entry.getName();
                Path entryPath = Path.of(entryName);
                if (entryName.startsWith("/")
                        || entryName.startsWith("\\")
                        || entryPath.isAbsolute()) {
                    throw new IOException("Unsafe absolute path in zip archive: " + entryName);
                }
                Path target = destinationRoot.resolve(entryName).normalize();
                if (!target.equals(destinationRoot) && !target.startsWith(destinationRoot)) {
                    throw new IOException("Unsafe path detected in zip archive: " + entryName);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(zipInputStream, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zipInputStream.closeEntry();
            }
        }
    }

    private static Path normalizeExtractedSkillDir(
            Path stagingDir, Path versionDir, RemoteSkill sourceSkill) throws IOException {
        Path skillDir =
                findExtractedSkillDir(stagingDir)
                        .orElseThrow(
                                () ->
                                        new IOException(
                                                "Skill "
                                                        + sourceSkill.name()
                                                        + " has no SKILL.md or skill.md after"
                                                        + " extraction."));
        Path skillMd =
                findSkillMd(skillDir)
                        .orElseThrow(
                                () ->
                                        new IOException(
                                                "Skill "
                                                        + sourceSkill.name()
                                                        + " has no SKILL.md or skill.md after"
                                                        + " extraction."));
        String declaredName = readFrontmatterName(skillMd);
        if (!isSafeDirName(declaredName)) {
            throw new IOException(
                    "Skill "
                            + sourceSkill.name()
                            + " has unsafe frontmatter name: "
                            + declaredName);
        }

        Path finalDir = versionDir.resolve(declaredName).toAbsolutePath().normalize();
        if (Files.exists(finalDir)) {
            deleteRecursively(finalDir);
        }
        if (skillDir.equals(stagingDir)) {
            Files.move(stagingDir, finalDir, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(skillDir, finalDir, StandardCopyOption.REPLACE_EXISTING);
            if (Files.exists(stagingDir)) {
                deleteRecursively(stagingDir);
            }
        }
        return finalDir;
    }

    private static Optional<Path> findExtractedSkillDir(Path stagingDir) throws IOException {
        if (findSkillMd(stagingDir).isPresent()) {
            return Optional.of(stagingDir);
        }
        try (Stream<Path> paths = Files.walk(stagingDir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> SKILL_MD_NAMES.contains(path.getFileName().toString()))
                    .map(Path::getParent)
                    .min(Comparator.comparingInt(path -> path.getNameCount()));
        }
    }

    private static Optional<Path> findSkillMd(Path skillDir) {
        return SKILL_MD_NAMES.stream()
                .map(skillDir::resolve)
                .filter(Files::isRegularFile)
                .findFirst();
    }

    private static boolean hasSkillMd(Path skillDir) {
        return findSkillMd(skillDir).isPresent();
    }

    private static String readFrontmatterName(Path skillMd) throws IOException {
        StringBuilder yaml = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(skillMd)) {
            String firstLine = reader.readLine();
            if (firstLine == null || !"---".equals(firstLine.trim())) {
                throw new IOException("Skill file must start with frontmatter.");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if ("---".equals(line.trim())) {
                    Map<String, Object> data =
                            YAML_MAPPER.readValue(
                                    yaml.toString(), new TypeReference<Map<String, Object>>() {});
                    Object name = data.get("name");
                    if (name == null || name.toString().isBlank()) {
                        throw new IOException("Skill frontmatter is missing name.");
                    }
                    return name.toString();
                }
                yaml.append(line).append('\n');
            }
        }
        throw new IOException("Skill file frontmatter is not closed.");
    }

    private static void validateSkillDir(Path skillDir) {
        new SingleSkillDirectorySource(skillDir).listFrontmatters().blockingGet();
    }

    private static void cleanupOldVersions(Path currentVersionDir) throws IOException {
        Path skillDir = currentVersionDir.getParent();
        if (skillDir == null || !Files.isDirectory(skillDir)) {
            return;
        }
        try (Stream<Path> versions = Files.list(skillDir)) {
            for (Path versionDir : versions.filter(Files::isDirectory).toList()) {
                if (!versionDir.equals(currentVersionDir)) {
                    deleteRecursively(versionDir);
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path child : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(child);
            }
        }
    }

    private static Path defaultCacheDir() {
        String configured = System.getenv("VEADK_SKILLS_CACHE_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath();
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "veadk", "skills");
    }

    private static String safeCachePart(String value) {
        StringBuilder builder = new StringBuilder();
        for (char ch : Objects.requireNonNullElse(value, "unknown").toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-') {
                builder.append(ch);
            } else {
                builder.append('_');
            }
        }
        String cleaned = builder.toString().replaceAll("^[._-]+|[._-]+$", "");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    private static boolean isSafeDirName(String value) {
        if (value == null || value.isBlank() || ".".equals(value) || "..".equals(value)) {
            return false;
        }
        Path path = Path.of(value);
        return !path.isAbsolute() && path.getNameCount() == 1;
    }
}
