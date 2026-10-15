/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.evaluation;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.genai.types.FunctionCall;
import com.volcengine.veadk.Runner;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Runs deterministic response and tool-trajectory evaluation against a VeADK runner. */
public final class OfflineEvaluator {
    public static final String TOOL_TRAJECTORY = "tool_trajectory_avg_score";
    public static final String RESPONSE_MATCH = "response_match_score";

    private final Runner runner;

    public OfflineEvaluator(Runner runner) {
        this.runner = Objects.requireNonNull(runner, "runner must be set.");
    }

    public EvaluationReport evaluate(EvalSet evalSet) {
        return evaluate(evalSet, EvaluationOptions.defaults());
    }

    public EvaluationReport evaluate(EvalSet evalSet, EvaluationOptions options) {
        Objects.requireNonNull(evalSet, "evalSet must be set.");
        Objects.requireNonNull(options, "options must be set.");
        List<CaseRunResult> results = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (EvalCase evalCase : evalSet.evalCases()) {
            for (int run = 1; run <= options.numRuns(); run++) {
                CaseRunResult result = runCase(evalCase, run, options);
                results.add(result);
                if (!result.passed()) {
                    failures.add(
                            "Evaluation case '"
                                    + evalCase.evalId()
                                    + "' failed on run "
                                    + run
                                    + ".");
                }
            }
        }
        return new EvaluationReport(evalSet.evalSetId(), results, failures);
    }

    private CaseRunResult runCase(EvalCase evalCase, int runNumber, EvaluationOptions options) {
        SessionInput input = evalCase.sessionInput();
        String userId = input == null || blank(input.userId()) ? "veadk_eval_user" : input.userId();
        String sessionId = "veadk-eval-" + UUID.randomUUID();
        Map<String, Object> state = input == null ? Map.of() : input.state();
        runner.sessionService()
                .createSession(runner.appName(), userId, state, sessionId)
                .blockingGet();

        List<InvocationResult> invocationResults = new ArrayList<>();
        for (EvalInvocation expected : evalCase.conversation()) {
            int before = events(userId, sessionId).size();
            String actualResponse =
                    runner.run(
                            userId,
                            sessionId,
                            expected.userContent().text(),
                            RunConfig.builder().autoCreateSession(false).build());
            List<Event> allEvents = events(userId, sessionId);
            List<Event> turnEvents =
                    allEvents.subList(Math.min(before, allEvents.size()), allEvents.size());
            List<ToolInvocation> actualTools = toolInvocations(turnEvents);
            List<ToolInvocation> expectedTools =
                    expected.intermediateData() == null
                            ? List.of()
                            : expected.intermediateData().toolUses();
            double toolScore = toolTrajectoryMatches(expectedTools, actualTools) ? 1.0 : 0.0;
            double responseScore = rouge1F1(expected.finalResponse().text(), actualResponse);
            List<MetricResult> metrics =
                    List.of(
                            new MetricResult(
                                    TOOL_TRAJECTORY,
                                    toolScore,
                                    options.toolScoreThreshold(),
                                    toolScore >= options.toolScoreThreshold()),
                            new MetricResult(
                                    RESPONSE_MATCH,
                                    responseScore,
                                    options.responseMatchScoreThreshold(),
                                    responseScore >= options.responseMatchScoreThreshold()));
            invocationResults.add(
                    new InvocationResult(
                            expected.invocationId(), actualResponse, actualTools, metrics));
        }
        boolean passed =
                invocationResults.stream()
                        .flatMap(result -> result.metrics().stream())
                        .allMatch(MetricResult::passed);
        return new CaseRunResult(evalCase.evalId(), runNumber, invocationResults, passed);
    }

    private List<Event> events(String userId, String sessionId) {
        return runner.sessionService()
                .listEvents(runner.appName(), userId, sessionId)
                .blockingGet()
                .events();
    }

    static List<ToolInvocation> toolInvocations(List<Event> events) {
        List<ToolInvocation> tools = new ArrayList<>();
        for (Event event : events) {
            for (FunctionCall call : event.functionCalls()) {
                tools.add(
                        new ToolInvocation(
                                call.id().orElse(null),
                                call.args().orElse(Map.of()),
                                call.name().orElse("")));
            }
        }
        return List.copyOf(tools);
    }

    private static boolean toolTrajectoryMatches(
            List<ToolInvocation> expected, List<ToolInvocation> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            ToolInvocation expectedTool = expected.get(i);
            ToolInvocation actualTool = actual.get(i);
            if (!Objects.equals(expectedTool.name(), actualTool.name())
                    || !valuesEqual(expectedTool.args(), actualTool.args())) return false;
        }
        return true;
    }

    private static boolean valuesEqual(Object expected, Object actual) {
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            try {
                return new BigDecimal(expectedNumber.toString())
                                .compareTo(new BigDecimal(actualNumber.toString()))
                        == 0;
            } catch (NumberFormatException ignored) {
                return Double.compare(expectedNumber.doubleValue(), actualNumber.doubleValue())
                        == 0;
            }
        }
        if (expected instanceof Map<?, ?> expectedMap && actual instanceof Map<?, ?> actualMap) {
            if (!expectedMap.keySet().equals(actualMap.keySet())) return false;
            return expectedMap.entrySet().stream()
                    .allMatch(
                            entry -> valuesEqual(entry.getValue(), actualMap.get(entry.getKey())));
        }
        if (expected instanceof List<?> expectedList && actual instanceof List<?> actualList) {
            if (expectedList.size() != actualList.size()) return false;
            for (int i = 0; i < expectedList.size(); i++) {
                if (!valuesEqual(expectedList.get(i), actualList.get(i))) return false;
            }
            return true;
        }
        return Objects.equals(expected, actual);
    }

    static double rouge1F1(String expected, String actual) {
        List<String> expectedTokens = tokens(expected);
        List<String> actualTokens = tokens(actual);
        if (expectedTokens.isEmpty() && actualTokens.isEmpty()) return 1.0;
        if (expectedTokens.isEmpty() || actualTokens.isEmpty()) return 0.0;
        Map<String, Integer> remaining = new HashMap<>();
        expectedTokens.forEach(token -> remaining.merge(token, 1, Integer::sum));
        int overlap = 0;
        for (String token : actualTokens) {
            int count = remaining.getOrDefault(token, 0);
            if (count > 0) {
                overlap++;
                remaining.put(token, count - 1);
            }
        }
        double precision = (double) overlap / actualTokens.size();
        double recall = (double) overlap / expectedTokens.size();
        return precision + recall == 0.0 ? 0.0 : 2.0 * precision * recall / (precision + recall);
    }

    private static List<String> tokens(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = text.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        normalized
                .codePoints()
                .forEach(
                        codePoint -> {
                            if (codePoint < 128 && Character.isLetterOrDigit(codePoint)) {
                                ascii.appendCodePoint(codePoint);
                            } else {
                                if (!ascii.isEmpty()) {
                                    result.add(ascii.toString());
                                    ascii.setLength(0);
                                }
                                if (Character.isLetterOrDigit(codePoint))
                                    result.add(new String(Character.toChars(codePoint)));
                            }
                        });
        if (!ascii.isEmpty()) result.add(ascii.toString());
        return result;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record EvaluationOptions(
            int numRuns, double toolScoreThreshold, double responseMatchScoreThreshold) {
        public EvaluationOptions {
            if (numRuns < 1) throw new IllegalArgumentException("numRuns must be at least 1.");
            checkThreshold(toolScoreThreshold, "toolScoreThreshold");
            checkThreshold(responseMatchScoreThreshold, "responseMatchScoreThreshold");
        }

        public static EvaluationOptions defaults() {
            return new EvaluationOptions(2, 1.0, 0.8);
        }

        private static void checkThreshold(double value, String name) {
            if (!Double.isFinite(value) || value < 0.0 || value > 1.0)
                throw new IllegalArgumentException(name + " must be between 0 and 1.");
        }
    }

    public record MetricResult(String metric, double score, double threshold, boolean passed) {}

    public record InvocationResult(
            String invocationId,
            String actualResponse,
            List<ToolInvocation> actualTools,
            List<MetricResult> metrics) {
        public InvocationResult {
            actualTools = List.copyOf(actualTools);
            metrics = List.copyOf(metrics);
        }
    }

    public record CaseRunResult(
            String evalId, int runNumber, List<InvocationResult> invocations, boolean passed) {
        public CaseRunResult {
            invocations = List.copyOf(invocations);
        }
    }

    public record EvaluationReport(
            String evalSetId, List<CaseRunResult> results, List<String> failures) {
        public EvaluationReport {
            results = List.copyOf(results);
            failures = List.copyOf(failures);
        }

        public boolean passed() {
            return failures.isEmpty();
        }
    }
}
