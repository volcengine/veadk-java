/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 * Licensed under the Apache License, Version 2.0.
 */
package com.volcengine.veadk.artifacts;

import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.ListArtifactsResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** Durable, session-isolated ADK artifact storage backed by the local filesystem. */
public final class LocalArtifactService implements BaseArtifactService {
    private static final String VERSION_PREFIX = "v";
    private static final String VERSION_SUFFIX = ".json";
    private final Path root;
    private final Object lock = new Object();

    public LocalArtifactService(Path root) {
        this.root = Objects.requireNonNull(root, "root must be set.").toAbsolutePath().normalize();
    }

    @Override
    public Single<Integer> saveArtifact(
            String app, String user, String session, String filename, Part artifact) {
        return Single.fromCallable(() -> save(app, user, session, filename, artifact));
    }

    private int save(String app, String user, String session, String filename, Part artifact)
            throws IOException {
        Objects.requireNonNull(artifact, "artifact must be set.");
        synchronized (lock) {
            Path directory = artifactDirectory(app, user, session, filename);
            Files.createDirectories(directory);
            int version = versions(directory).stream().max(Integer::compareTo).orElse(-1) + 1;
            Path target = versionFile(directory, version);
            Path temporary = Files.createTempFile(directory, ".artifact-", ".tmp");
            try {
                Files.writeString(temporary, artifact.toJson(), StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return version;
        }
    }

    @Override
    public Maybe<Part> loadArtifact(
            String app, String user, String session, String filename, Integer version) {
        return Maybe.fromCallable(() -> load(app, user, session, filename, version));
    }

    private Part load(String app, String user, String session, String filename, Integer requested)
            throws IOException {
        Path directory = artifactDirectory(app, user, session, filename);
        List<Integer> available = versions(directory);
        if (available.isEmpty()) return null;
        int version = requested == null ? available.get(available.size() - 1) : requested;
        Path source = versionFile(directory, version);
        if (!Files.isRegularFile(source)) return null;
        return Part.fromJson(Files.readString(source, StandardCharsets.UTF_8));
    }

    @Override
    public Single<ListArtifactsResponse> listArtifactKeys(String app, String user, String session) {
        return Single.fromCallable(
                () -> {
                    Path directory = sessionDirectory(app, user, session);
                    if (!Files.isDirectory(directory))
                        return ListArtifactsResponse.builder().filenames(List.of()).build();
                    try (var paths = Files.list(directory)) {
                        List<String> names =
                                paths.filter(Files::isDirectory)
                                        .map(path -> decode(path.getFileName().toString()))
                                        .sorted()
                                        .toList();
                        return ListArtifactsResponse.builder().filenames(names).build();
                    }
                });
    }

    @Override
    public Completable deleteArtifact(String app, String user, String session, String filename) {
        return Completable.fromAction(
                () -> {
                    synchronized (lock) {
                        Path directory = artifactDirectory(app, user, session, filename);
                        if (!Files.isDirectory(directory)) return;
                        try (var paths = Files.list(directory)) {
                            for (Path path : paths.toList()) Files.deleteIfExists(path);
                        }
                        Files.deleteIfExists(directory);
                    }
                });
    }

    @Override
    public Single<ImmutableList<Integer>> listVersions(
            String app, String user, String session, String filename) {
        return Single.fromCallable(
                () ->
                        ImmutableList.copyOf(
                                versions(artifactDirectory(app, user, session, filename))));
    }

    private List<Integer> versions(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (var paths = Files.list(directory)) {
            return paths.map(path -> parseVersion(path.getFileName().toString()))
                    .filter(value -> value >= 0)
                    .sorted()
                    .toList();
        }
    }

    private Path artifactDirectory(String app, String user, String session, String filename) {
        return sessionDirectory(app, user, session).resolve(encode(required(filename, "filename")));
    }

    private Path sessionDirectory(String app, String user, String session) {
        return root.resolve(encode(required(app, "app")))
                .resolve(encode(required(user, "user")))
                .resolve(encode(required(session, "session")));
    }

    private static Path versionFile(Path directory, int version) {
        return directory.resolve(VERSION_PREFIX + String.format("%08d", version) + VERSION_SUFFIX);
    }

    private static int parseVersion(String name) {
        if (!name.startsWith(VERSION_PREFIX) || !name.endsWith(VERSION_SUFFIX)) return -1;
        try {
            return Integer.parseInt(name.substring(1, name.length() - VERSION_SUFFIX.length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(label + " must be set.");
        return value;
    }
}
