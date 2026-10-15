package com.shijie.resumehelper.interview;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.shijie.resumehelper.interview.InterviewQuestion.*;
import static org.junit.jupiter.api.Assertions.*;

class InterviewScoringPolicyTest {
    private final InterviewScoringPolicy policy = new InterviewScoringPolicy();

    @Test void weightedScoreUsesOriginalWeights() {
        assertEquals(new BigDecimal("80.0"), policy.overallScore(List.of(80, 90, 70), List.of(8, 12, 12)));
        assertEquals(List.of(new BigDecimal("25.00"), new BigDecimal("37.50"), new BigDecimal("37.50")),
                policy.percentages(List.of(8, 12, 12)));
        // Rounded display percentages must not be reused in score arithmetic.
        assertEquals(new BigDecimal("66.7"), policy.overallScore(List.of(0, 100, 100), List.of(1, 1, 1)));
    }

    @Test void roundedPercentagesStillSumToOneHundredWithStableTies() {
        var result = policy.percentages(List.of(1, 1, 1));
        assertEquals(new BigDecimal("100.00"), result.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(List.of(new BigDecimal("33.34"), new BigDecimal("33.33"), new BigDecimal("33.33")), result);
        assertEquals(List.of(new BigDecimal("16.67"), new BigDecimal("33.33"), new BigDecimal("50.00")),
                policy.percentages(List.of(1, 2, 3)));
    }

    @Test void handlesOneQuestionAndScoreExtremes() {
        assertEquals(new BigDecimal("75.0"), policy.overallScore(List.of(75), List.of(18)));
        assertEquals(List.of(new BigDecimal("100.00")), policy.percentages(List.of(18)));
        assertEquals(new BigDecimal("0.0"), policy.overallScore(List.of(0, 0), List.of(4, 18)));
        assertEquals(new BigDecimal("100.0"), policy.overallScore(List.of(100, 100), List.of(4, 18)));
        assertEquals(new BigDecimal("0.1"), policy.overallScore(List.of(0, 1), List.of(19, 1)));
    }

    @Test void usesLongArithmeticForLargeWeights() {
        assertEquals(new BigDecimal("50.0"), policy.overallScore(List.of(0, 100),
                List.of(Integer.MAX_VALUE, Integer.MAX_VALUE)));
        assertEquals(List.of(new BigDecimal("50.00"), new BigDecimal("50.00")),
                policy.percentages(List.of(Integer.MAX_VALUE, Integer.MAX_VALUE)));
    }

    @Test void rejectsIncompleteOrInvalidWeightedInputs() {
        assertThrows(IllegalArgumentException.class, () -> policy.overallScore(List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> policy.overallScore(List.of(10), List.of(4, 8)));
        assertThrows(IllegalArgumentException.class, () -> policy.overallScore(null, List.of(4)));
        assertThrows(IllegalArgumentException.class, () -> policy.overallScore(List.of(10), null));
        for (Integer score : Arrays.asList(-1, 101, null)) {
            assertThrows(IllegalArgumentException.class,
                    () -> policy.overallScore(Arrays.asList(score), List.of(4)));
        }
        for (Integer weight : Arrays.asList(-1, 0, null)) {
            assertThrows(IllegalArgumentException.class,
                    () -> policy.overallScore(List.of(10), Arrays.asList(weight)));
            assertThrows(IllegalArgumentException.class, () -> policy.percentages(Arrays.asList(weight)));
        }
        assertThrows(IllegalArgumentException.class, () -> policy.percentages(List.of()));
        assertThrows(IllegalArgumentException.class, () -> policy.percentages(null));
    }

    @Test void everyImportanceAndDifficultyCombinationUsesFrozenCoefficients() {
        int[][] expected = {{12, 15, 18}, {8, 10, 12}, {4, 5, 6}};
        for (Importance importance : Importance.values()) {
            for (Difficulty difficulty : Difficulty.values()) {
                assertEquals(expected[importance.ordinal()][difficulty.ordinal()], policy.weightUnits(importance, difficulty));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> policy.weightUnits(null, Difficulty.BASIC));
        assertThrows(IllegalArgumentException.class, () -> policy.weightUnits(Importance.CORE, null));
    }

    @Test void scoresExactlyFiveDimensionsInFivePointBands() {
        assertEquals(0, policy.questionScore(scores(0)));
        assertEquals(25, policy.questionScore(scores(5)));
        assertEquals(50, policy.questionScore(scores(10)));
        assertEquals(75, policy.questionScore(scores(15)));
        assertEquals(100, policy.questionScore(scores(20)));
        var mixed = scores(15);
        mixed.put(Dimension.CORRECTNESS, 20);
        assertEquals(80, policy.questionScore(mixed));
    }

    @Test void rejectsMissingOrInvalidDimensionBands() {
        assertThrows(IllegalArgumentException.class, () -> policy.questionScore(null));
        assertThrows(IllegalArgumentException.class, () -> policy.questionScore(Map.of()));
        var incomplete = scores(10);
        incomplete.remove(Dimension.CLARITY);
        assertThrows(IllegalArgumentException.class, () -> policy.questionScore(incomplete));
        for (Integer value : Arrays.asList(-5, 1, 21, 25, null)) {
            var invalid = scores(10);
            invalid.put(Dimension.REASONING, value);
            assertThrows(IllegalArgumentException.class, () -> policy.questionScore(invalid));
        }
    }

    @Test void questionSnapshotsCriteriaAndPreservesOriginalText() {
        var criteria = new ArrayList<>(criteria());
        var question = question(" 请说明验证方法？ ", criteria);
        criteria.clear();
        assertEquals(" 请说明验证方法？ ", question.text());
        assertEquals(5, question.criteria().size());
        assertEquals(InterviewScoringPolicy.VERSION, question.rubricVersion());
        assertThrows(UnsupportedOperationException.class, () -> question.criteria().clear());
    }

    @Test void rejectsBlankAndOversizedQuestionContent() {
        for (String text : Arrays.asList(null, " ", "问".repeat(501))) {
            assertThrows(IllegalArgumentException.class, () -> question(text, criteria()));
        }
        assertDoesNotThrow(() -> question("问".repeat(500), criteria()));
        assertThrows(IllegalArgumentException.class, () -> new InterviewQuestion("题", "能".repeat(201),
                Importance.CORE, Difficulty.BASIC, "原因", criteria(), InterviewScoringPolicy.VERSION));
        assertThrows(IllegalArgumentException.class, () -> new InterviewQuestion("题", "能力",
                Importance.CORE, Difficulty.BASIC, "因".repeat(201), criteria(), InterviewScoringPolicy.VERSION));
        assertThrows(IllegalArgumentException.class, () -> new InterviewQuestion("题", " ",
                Importance.CORE, Difficulty.BASIC, "原因", criteria(), InterviewScoringPolicy.VERSION));
        assertThrows(IllegalArgumentException.class, () -> new InterviewQuestion("题", "能力",
                null, Difficulty.BASIC, "原因", criteria(), InterviewScoringPolicy.VERSION));
        assertThrows(IllegalArgumentException.class, () -> new InterviewQuestion("题", "能力",
                Importance.CORE, null, "原因", criteria(), InterviewScoringPolicy.VERSION));
        assertThrows(IllegalArgumentException.class, () -> new InterviewQuestion("题", "能力",
                Importance.CORE, Difficulty.BASIC, "原因", criteria(), " "));
    }

    @Test void criteriaMustCoverEveryDimensionOnce() {
        assertThrows(IllegalArgumentException.class, () -> question("题", null));
        assertThrows(IllegalArgumentException.class, () -> question("题", criteria().subList(0, 4)));
        var duplicate = new ArrayList<>(criteria());
        duplicate.set(4, duplicate.get(0));
        assertThrows(IllegalArgumentException.class, () -> question("题", duplicate));
        var withNull = new ArrayList<>(criteria());
        withNull.set(4, null);
        assertThrows(IllegalArgumentException.class, () -> question("题", withNull));
        assertThrows(IllegalArgumentException.class, () -> new Criterion(null, "标准"));
        assertThrows(IllegalArgumentException.class, () -> new Criterion(Dimension.CLARITY, " "));
        assertThrows(IllegalArgumentException.class, () -> new Criterion(Dimension.CLARITY, "标".repeat(501)));
        assertDoesNotThrow(() -> new Criterion(Dimension.CLARITY, "标".repeat(500)));
    }

    @Test void assessmentSnapshotsScoresAndFeedback() {
        var dimensions = scores(15);
        var evidence = new ArrayList<>(List.of("我比较了相同接口"));
        var missing = new ArrayList<>(List.of("未说明样本量"));
        var result = assessment(dimensions, evidence, missing, "说明验证方法", "补充真实样本", "指标、方法、结果");
        dimensions.put(Dimension.CLARITY, 0);
        evidence.clear();
        missing.clear();
        assertEquals(15, result.dimensionScores().get(Dimension.CLARITY));
        assertEquals(List.of("我比较了相同接口"), result.evidence());
        assertEquals(List.of("未说明样本量"), result.missingPoints());
        assertThrows(UnsupportedOperationException.class, () -> result.dimensionScores().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.evidence().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.missingPoints().clear());
    }

    @Test void zeroScoreMayHaveNoFactualEvidenceAndCompleteAnswerMayHaveNoMissingPoints() {
        assertDoesNotThrow(() -> assessment(scores(0), List.of(), List.of("没有有效支持"),
                "暂无已验证亮点", "补充真实方法", "指标、方法、结果"));
        assertDoesNotThrow(() -> assessment(scores(20), List.of("完整说明"), List.of(),
                "覆盖全部标准", "继续变体复练", "指标、方法、结果"));
    }

    @Test void rejectsInvalidAssessmentDimensionsAndRequiredFeedback() {
        assertThrows(IllegalArgumentException.class, () -> assessment(Map.of(), List.of(), List.of(), "亮点", "建议", "框架"));
        var invalid = scores(10);
        invalid.put(Dimension.EVIDENCE, 11);
        assertThrows(IllegalArgumentException.class, () -> assessment(invalid, List.of(), List.of(), "亮点", "建议", "框架"));
        for (String value : Arrays.asList(null, " ", "字".repeat(1001))) {
            assertThrows(IllegalArgumentException.class, () -> assessment(scores(10), List.of(), List.of(), value, "建议", "框架"));
            assertThrows(IllegalArgumentException.class, () -> assessment(scores(10), List.of(), List.of(), "亮点", value, "框架"));
            assertThrows(IllegalArgumentException.class, () -> assessment(scores(10), List.of(), List.of(), "亮点", "建议", value));
        }
        assertThrows(IllegalArgumentException.class, () -> new InterviewAssessment(" ", scores(10),
                List.of(), List.of(), "亮点", "建议", "框架", "model"));
    }

    @Test void rejectsUnboundedOrEmptyFeedbackItems() {
        for (List<String> items : Arrays.<List<String>>asList(null, List.of(" "), Arrays.asList((String) null),
                List.of("字".repeat(1001)), java.util.Collections.nCopies(11, "证据"))) {
            assertThrows(IllegalArgumentException.class, () -> assessment(scores(10), items, List.of(), "亮点", "建议", "框架"));
            assertThrows(IllegalArgumentException.class, () -> assessment(scores(10), List.of(), items, "亮点", "建议", "框架"));
        }
    }

    private static InterviewQuestion question(String text, List<Criterion> criteria) {
        return new InterviewQuestion(text, "指标验证", Importance.RELATED, Difficulty.BASIC,
                "与岗位相关", criteria, InterviewScoringPolicy.VERSION);
    }

    private static List<Criterion> criteria() {
        return Arrays.stream(Dimension.values()).map(d -> new Criterion(d, "本题标准：" + d)).toList();
    }

    private static Map<Dimension, Integer> scores(int value) {
        Map<Dimension, Integer> scores = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values()) scores.put(dimension, value);
        return scores;
    }

    private static InterviewAssessment assessment(Map<Dimension, Integer> scores, List<String> evidence,
            List<String> missing, String highlight, String improvement, String outline) {
        return new InterviewAssessment("question-1", scores, evidence, missing, highlight, improvement, outline, "test-model");
    }
}
