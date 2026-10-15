/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.evaluation;

import com.volcengine.veadk.Runner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** veadk-python compatible entry point for deterministic ADK evaluation. */
public final class ADKEvaluator {
    private final OfflineEvaluator evaluator;
    private final EvalSetLoader loader;

    public ADKEvaluator(Runner runner) {
        this.evaluator = new OfflineEvaluator(runner);
        this.loader = new EvalSetLoader();
    }

    public OfflineEvaluator.EvaluationReport evaluate(EvalSet evalSet) {
        return evaluator.evaluate(evalSet);
    }

    public OfflineEvaluator.EvaluationReport evaluate(
            EvalSet evalSet, OfflineEvaluator.EvaluationOptions options) {
        return evaluator.evaluate(evalSet, options);
    }

    public List<OfflineEvaluator.EvaluationReport> evaluate(Path fileOrDirectory)
            throws IOException {
        return evaluate(fileOrDirectory, OfflineEvaluator.EvaluationOptions.defaults());
    }

    public List<OfflineEvaluator.EvaluationReport> evaluate(
            Path fileOrDirectory, OfflineEvaluator.EvaluationOptions options) throws IOException {
        List<OfflineEvaluator.EvaluationReport> reports = new ArrayList<>();
        for (EvalSet evalSet : loader.loadAll(fileOrDirectory)) {
            reports.add(evaluator.evaluate(evalSet, options));
        }
        return List.copyOf(reports);
    }
}
