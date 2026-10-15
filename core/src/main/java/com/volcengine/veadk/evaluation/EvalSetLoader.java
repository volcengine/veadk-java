/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Loads and validates ADK-compatible evaluation set files. */
public final class EvalSetLoader {
    private final ObjectMapper mapper;

    public EvalSetLoader() {
        this(new ObjectMapper().registerModule(new Jdk8Module()));
    }

    EvalSetLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public EvalSet load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file))
            throw new IllegalArgumentException("Evaluation set file does not exist: " + file);
        EvalSet evalSet = mapper.readValue(file.toFile(), EvalSet.class);
        validate(evalSet, file);
        return evalSet;
    }

    public List<EvalSet> loadAll(Path fileOrDirectory) throws IOException {
        if (fileOrDirectory == null || !Files.exists(fileOrDirectory))
            throw new IllegalArgumentException(
                    "Evaluation set path does not exist: " + fileOrDirectory);
        if (Files.isRegularFile(fileOrDirectory)) return List.of(load(fileOrDirectory));
        List<Path> files;
        try (var paths = Files.walk(fileOrDirectory)) {
            files =
                    paths.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".test.json"))
                            .sorted(Comparator.naturalOrder())
                            .toList();
        }
        if (files.isEmpty())
            throw new IllegalArgumentException(
                    "No *.test.json evaluation sets found under: " + fileOrDirectory);
        List<EvalSet> result = new ArrayList<>(files.size());
        for (Path file : files) result.add(load(file));
        return List.copyOf(result);
    }

    private static void validate(EvalSet evalSet, Path source) {
        if (evalSet == null || blank(evalSet.evalSetId()))
            throw new IllegalArgumentException("Evaluation set has a blank eval_set_id: " + source);
        if (evalSet.evalCases().isEmpty())
            throw new IllegalArgumentException("Evaluation set contains no eval_cases: " + source);
        for (EvalCase evalCase : evalSet.evalCases()) {
            if (evalCase == null || blank(evalCase.evalId()))
                throw new IllegalArgumentException(
                        "Evaluation case has a blank eval_id: " + source);
            if (evalCase.conversation().isEmpty())
                throw new IllegalArgumentException(
                        "Evaluation case " + evalCase.evalId() + " has no conversation turns.");
            for (EvalInvocation invocation : evalCase.conversation()) {
                if (invocation == null || invocation.userContent() == null)
                    throw new IllegalArgumentException(
                            "Evaluation case " + evalCase.evalId() + " has no user_content.");
                if (invocation.finalResponse() == null)
                    throw new IllegalArgumentException(
                            "Evaluation case " + evalCase.evalId() + " has no final_response.");
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
