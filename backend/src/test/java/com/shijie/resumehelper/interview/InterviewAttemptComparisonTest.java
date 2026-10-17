package com.shijie.resumehelper.interview;

import static org.junit.jupiter.api.Assertions.*;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class InterviewAttemptComparisonTest {
    @Test void reportsFixedRemainingAndNewIssuesWithoutExactDeltaAcrossModelsOrRubrics() {
        var baseline = assessment("q1", 50, "model-a", List.of("补充统计周期", "说明样本范围"));
        var attempt = assessment("q1", 80, "model-b", List.of("说明样本范围", "验证对照方法"));
        var result = InterviewAttemptComparison.compare(baseline, attempt, "rubric-v1", "rubric-v1");
        assertEquals(List.of("补充统计周期"), result.fixedPoints());
        assertEquals(List.of("说明样本范围", "验证对照方法"), result.remainingPoints());
        assertEquals(List.of("验证对照方法"), result.newIssues());
        assertNull(result.scoreChange());
        assertTrue(result.trainingReference());
    }

    @Test void sameModelAndFrozenRubricMayShowOnlyATrainingReferenceDelta() {
        var baseline = assessment("q1", 50, "same-model", List.of("补充统计周期"));
        var attempt = assessment("q1", 75, "same-model", List.of());
        var result = InterviewAttemptComparison.compare(baseline, attempt, "rubric-v1", "rubric-v1");
        assertEquals(25, result.scoreChange());
        assertTrue(result.trainingReference());
        assertTrue(result.comparisonNote().contains("训练参考"));
    }

    @Test void absentBaselineOrChangedRubricNeverClaimsAnExactScoreChange() {
        var attempt = assessment("q1", 75, "same-model", List.of());
        assertNull(InterviewAttemptComparison.compare(null, attempt, "rubric-v1", "rubric-v1").scoreChange());
        var baseline = assessment("q1", 50, "same-model", List.of("补充统计周期"));
        assertNull(InterviewAttemptComparison.compare(baseline, attempt, "rubric-v1", "rubric-v2").scoreChange());
    }

    private static InterviewAssessment assessment(String id, int score, String model, List<String> missing) {
        var dimensions = new EnumMap<InterviewQuestion.Dimension, Integer>(InterviewQuestion.Dimension.class);
        int left = score;
        for (var dimension : InterviewQuestion.Dimension.values()) {
            int value = Math.min(20, left / 5 * 5);
            dimensions.put(dimension, value); left -= value;
        }
        return new InterviewAssessment(id, dimensions, List.of(), missing, "可核对的回答表现",
                "继续补足真实依据", "事实、行动、结果", model);
    }
}
