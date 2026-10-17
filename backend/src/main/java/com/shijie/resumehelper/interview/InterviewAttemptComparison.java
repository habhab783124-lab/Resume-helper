package com.shijie.resumehelper.interview;

import java.util.List;

/** Evidence-oriented comparison between the first answer and an independent practice attempt. */
public record InterviewAttemptComparison(Integer scoreChange, List<String> fixedPoints,
        List<String> remainingPoints, List<String> newIssues, boolean trainingReference,
        String comparisonNote) {
    public InterviewAttemptComparison {
        fixedPoints = fixedPoints == null ? List.of() : List.copyOf(fixedPoints);
        remainingPoints = remainingPoints == null ? List.of() : List.copyOf(remainingPoints);
        newIssues = newIssues == null ? List.of() : List.copyOf(newIssues);
        comparisonNote = comparisonNote == null ? "" : comparisonNote;
    }

    public static InterviewAttemptComparison compare(InterviewAssessment baseline,
            InterviewAssessment attempt, String baselineRubric, String attemptRubric) {
        List<String> before = baseline == null ? List.of() : baseline.missingPoints();
        List<String> after = attempt == null ? List.of() : attempt.missingPoints();
        var fixed = before.stream().filter(point -> !after.contains(point)).toList();
        var added = after.stream().filter(point -> !before.contains(point)).toList();
        boolean sameRubric = baseline != null && baselineRubric != null && !baselineRubric.isBlank()
                && baselineRubric.equals(attemptRubric);
        boolean sameModel = baseline != null && attempt != null && !baseline.aiModel().isBlank()
                && baseline.aiModel().equals(attempt.aiModel());
        Integer delta = sameRubric && sameModel
                ? new InterviewScoringPolicy().questionScore(attempt.dimensionScores())
                    - new InterviewScoringPolicy().questionScore(baseline.dimensionScores())
                : null;
        String note = delta == null
                ? "缺少可比的首答评分，或模型/评分标准不同；仅展示证据和缺项变化，不计算提分。"
                : "同一模型与冻结标准下的训练参考变化，不代表经过外部校准的提分。";
        return new InterviewAttemptComparison(delta, fixed, after, added, true, note);
    }
}
