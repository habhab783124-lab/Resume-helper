package com.shijie.resumehelper.interview;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Validated feedback; evidence provenance is checked against the answer by the agent. */
public record InterviewAssessment(
        String questionId, Map<InterviewQuestion.Dimension, Integer> dimensionScores,
        List<String> evidence, List<String> missingPoints,
        String highlight, String improvement, String suggestedOutline, String aiModel) {
    public InterviewAssessment {
        if (questionId == null || questionId.isBlank())
            throw new IllegalArgumentException("题目 ID 不能为空");
        new InterviewScoringPolicy().questionScore(dimensionScores);
        dimensionScores = Collections.unmodifiableMap(new EnumMap<>(dimensionScores));
        evidence = feedbackItems(evidence, "回答证据");
        missingPoints = feedbackItems(missingPoints, "缺失要点");
        InterviewQuestion.requireText(highlight, 1000, "回答亮点");
        InterviewQuestion.requireText(improvement, 1000, "改进动作");
        InterviewQuestion.requireText(suggestedOutline, 1000, "回答框架");
        aiModel = aiModel == null ? "" : aiModel;
    }

    private static List<String> feedbackItems(List<String> items, String field) {
        if (items == null || items.size() > 10)
            throw new IllegalArgumentException(field + "不能超过十项");
        for (String item : items) InterviewQuestion.requireText(item, 1000, field);
        return List.copyOf(items);
    }
}
