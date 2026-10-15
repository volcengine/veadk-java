/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.evaluation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.volcengine.veadk.Runner;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exports a runner session as an ADK-compatible evaluation set. */
public final class EvalSetRecorder {
    private final ObjectMapper mapper;

    public EvalSetRecorder() {
        this(new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL));
    }

    EvalSetRecorder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Path save(
            Runner runner,
            String userId,
            String sessionId,
            String evalSetId,
            String evalId,
            Path outputDirectory)
            throws IOException {
        Objects.requireNonNull(runner, "runner must be set.");
        requireText(userId, "userId");
        requireText(sessionId, "sessionId");
        requireText(evalSetId, "evalSetId");
        requireText(evalId, "evalId");
        Objects.requireNonNull(outputDirectory, "outputDirectory must be set.");
        Session session =
                runner.sessionService()
                        .getSession(runner.appName(), userId, sessionId, Optional.empty())
                        .blockingGet();
        List<Event> events =
                runner.sessionService()
                        .listEvents(runner.appName(), userId, sessionId)
                        .blockingGet()
                        .events();
        List<EvalInvocation> conversation = toConversation(events);
        if (conversation.isEmpty())
            throw new IllegalArgumentException("Session contains no completed user turns.");
        EvalSet evalSet =
                new EvalSet(
                        evalSetId,
                        evalSetId,
                        null,
                        List.of(
                                new EvalCase(
                                        evalId,
                                        conversation,
                                        new SessionInput(
                                                session.appName(),
                                                session.userId(),
                                                session.state()))));
        return writeAtomically(evalSet, outputDirectory);
    }

    static List<EvalInvocation> toConversation(List<Event> events) {
        List<EvalInvocation> conversation = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            Event userEvent = events.get(i);
            if (!"user".equals(userEvent.author())) continue;
            String invocationId = userEvent.invocationId();
            List<Event> turn = new ArrayList<>();
            int next = i + 1;
            while (next < events.size() && !"user".equals(events.get(next).author())) {
                turn.add(events.get(next));
                next++;
            }
            String response = finalResponse(turn);
            if (response != null) {
                conversation.add(
                        new EvalInvocation(
                                invocationId,
                                EvalContent.userText(text(userEvent)),
                                EvalContent.responseText(response),
                                new IntermediateData(OfflineEvaluator.toolInvocations(turn))));
            }
            i = next - 1;
        }
        return List.copyOf(conversation);
    }

    private Path writeAtomically(EvalSet evalSet, Path outputDirectory) throws IOException {
        Path directory = outputDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        String safeId = evalSet.evalSetId().replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeId.isBlank() || safeId.equals(".") || safeId.equals(".."))
            throw new IllegalArgumentException("evalSetId cannot form a safe file name.");
        Path target = directory.resolve(safeId + ".evalset.json").normalize();
        if (!target.startsWith(directory))
            throw new IllegalArgumentException("Evaluation output must remain in outputDirectory.");
        EvalSet valueToWrite = appendExisting(target, evalSet);
        Path temporary = Files.createTempFile(directory, safeId + "-", ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), valueToWrite);
            try {
                return Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                return Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private EvalSet appendExisting(Path target, EvalSet incoming) throws IOException {
        if (!Files.exists(target)) return incoming;
        EvalSet existing = mapper.readValue(target.toFile(), EvalSet.class);
        if (!incoming.evalSetId().equals(existing.evalSetId()))
            throw new IllegalStateException(
                    "Existing evaluation file has a different eval_set_id.");
        String newEvalId = incoming.evalCases().get(0).evalId();
        if (existing.evalCases().stream().anyMatch(value -> newEvalId.equals(value.evalId())))
            throw new IllegalArgumentException(
                    "Evaluation case already exists in set: " + newEvalId);
        List<EvalCase> cases = new ArrayList<>(existing.evalCases());
        cases.addAll(incoming.evalCases());
        return new EvalSet(existing.evalSetId(), existing.name(), existing.description(), cases);
    }

    private static String finalResponse(List<Event> events) {
        String fallback = null;
        for (Event event : events) {
            String value = text(event);
            if (!value.isBlank()) fallback = value;
            if (event.finalResponse() && !value.isBlank()) return value;
        }
        return fallback;
    }

    private static String text(Event event) {
        return event.content().map(Content::text).orElse("");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " must be set.");
        return value.trim();
    }
}
