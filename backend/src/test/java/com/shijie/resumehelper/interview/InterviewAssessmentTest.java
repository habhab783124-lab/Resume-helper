package com.shijie.resumehelper.interview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static com.shijie.resumehelper.interview.InterviewTestFixtures.*;

class InterviewAssessmentTest {
    private InterviewAgent.AssessmentInput input(String id, String answer) {
        return new InterviewAgent.AssessmentInput(new InterviewService.Turn(id, "问题", answer),
                contract("CORE", "INTERMEDIATE"));
    }
    @Test void usesFrozenContractAndReturnsIdKeyedAssessments() {
        var prompt = new AtomicReference<String>();
        var result = new InterviewAgent().assessAnswers("简历", "Java",
                List.of(input("q1", "我的回答"), input("q2", "不同回答")), List.of(), (s, p) -> {
                    prompt.set(p);
                    return json(Map.of("overallScore", 100, "weight", 999,
                            "assessments", List.of(assessment("q2", "不同回答", 70), assessment("q1", "我的回答", 80))));
                });
        assertEquals(Set.of("q1", "q2"), result.keySet());
        assertEquals(80, new InterviewScoringPolicy().questionScore(result.get("q1").dimensionScores()));
        assertTrue(prompt.get().contains("说明本题的关键事实和依据"));
        assertTrue(prompt.get().contains(InterviewScoringPolicy.VERSION));
    }
    @Test void fabricatedEvidenceAndDuplicateIdsCannotBecomeValidScores() {
        var valid = assessment("q1", "我的回答", 80);
        var result = new InterviewAgent().assessAnswers("简历", "Java",
                List.of(input("q1", "我的回答"), input("q2", "实际回答")), List.of(), (s, p) ->
                        json(Map.of("assessments", List.of(valid, valid, assessment("q2", "编造的证据", 100),
                                assessment("unknown", "我的回答", 100)))));
        assertTrue(result.isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"1.5", "25", "-5", "1000000000000"})
    void invalidDimensionDoesNotDiscardOtherValidItems(String value) {
        String invalid = json(assessment("q2", "我的回答", 80)).replace("\"CORRECTNESS\":20", "\"CORRECTNESS\":" + value);
        var result = new InterviewAgent().assessAnswers("简历", "Java",
                List.of(input("q1", "我的回答"), input("q2", "我的回答")), List.of(),
                (s, p) -> "{\"assessments\":[" + json(assessment("q1", "我的回答", 80)) + "," + invalid + "]}");
        assertEquals(Set.of("q1"), result.keySet());
    }
    @Test void supportsZeroEvidenceAndNormalizesWhitespaceForActualQuotes() {
        var zero = assessment("q1", "答不上来", 0);
        zero.put("evidence", List.of());
        zero.put("highlight", "尚未提供可支持评分标准的事实");
        var quoted = assessment("q2", "我实现 缓存", 60);
        var result = new InterviewAgent().assessAnswers("简历", "Java",
                List.of(input("q1", "答不上来"), input("q2", "我实现\n 缓存")), List.of(),
                (s, p) -> json(Map.of("assessments", List.of(zero, quoted))));
        assertEquals(0, new InterviewScoringPolicy().questionScore(result.get("q1").dimensionScores()));
        assertTrue(result.containsKey("q2"));
    }
    @Test void unavailableOrMalformedModelProducesNoInventedScore() {
        var agent = new InterviewAgent();
        var inputs = List.of(input("q1", "我的回答"));
        assertTrue(agent.assessAnswers("简历", "Java", inputs, List.of(), null).isEmpty());
        assertTrue(agent.assessAnswers("简历", "Java", inputs, List.of(), (s, p) -> "不是JSON").isEmpty());
        assertTrue(agent.assessAnswers("简历", "Java", inputs, List.of(),
                (s, p) -> { throw new com.shijie.resumehelper.common.ai.AiClient.AiException("不可用"); }).isEmpty());
    }

    @Test void positiveScoreWithoutAnyQuotedEvidenceRemainsPending() {
        var unsupported = assessment("q1", "我的回答", 100);
        unsupported.put("evidence", List.of());
        var result = new InterviewAgent().assessAnswers("简历", "Java", List.of(input("q1", "我的回答")),
                List.of(), (s, p) -> json(Map.of("assessments", List.of(unsupported))));
        assertTrue(result.isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"\u00a0", "\u2007", "\u202f"})
    void unicodeBlankQuotesCannotSupportPositiveScores(String quote) {
        var unsupported = assessment("q1", "我的回答", 100);
        unsupported.put("evidence", List.of(quote));
        var result = new InterviewAgent().assessAnswers("简历", "Java", List.of(input("q1", "我的回答")),
                List.of(), (s, p) -> json(Map.of("assessments", List.of(unsupported))));
        assertTrue(result.isEmpty());
    }
}
