package com.shijie.resumehelper.interview;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.shijie.resumehelper.interview.InterviewTestFixtures.*;

class InterviewReportAssemblerTest {
    private final List<InterviewService.Turn> turns = List.of(
            new InterviewService.Turn("q1", "第一题", "我的回答"),
            new InterviewService.Turn("q2", "第二题", "我的回答"),
            new InterviewService.Turn("q3", "第三题", "我的回答"));
    private final Map<String, InterviewQuestion> contracts = Map.of(
            "q1", contract("RELATED", "BASIC"), "q2", contract("RELATED", "ADVANCED"),
            "q3", contract("CORE", "BASIC"));
    @Test void calculatesWeightedScoreAndUsesCanonicalQuestionOrder() {
        var report = new InterviewReportAssembler().assemble(3, turns, contracts,
                Map.of("q3", assessed("q3", 70), "q1", assessed("q1", 80), "q2", assessed("q2", 90)));
        assertNull(report.overallScore());
        assertEquals("COMPLETE", report.reportStatus());
        assertEquals(List.of("q1", "q2", "q3"), report.turnFeedbacks().stream().map(InterviewAgent.TurnFeedback::questionId).toList());
        assertEquals(new BigDecimal("20.00"), report.turnFeedbacks().get(0).weightPercent());
        assertEquals("第一题", report.turnFeedbacks().get(0).question());
    }
    @Test void fullThirteenUsesUnroundedMainUnitsAndFixedAlgorithmShare() {
        int[] units = {12, 5, 15, 18, 5, 4, 6, 12, 10, 18, 10, 15};
        int[] scores = {20, 80, 0, 30, 5, 25, 40, 100, 70, 25, 60, 40};
        var history = new ArrayList<InterviewService.Turn>();
        var frozen = new LinkedHashMap<String, InterviewQuestion>();
        var results = new LinkedHashMap<String, InterviewAssessment>();
        var contexts = new LinkedHashMap<String, InterviewService.Context>();
        var policy = new InterviewScoringPolicy();
        for (int i = 0; i < 13; i++) {
            String id = "q" + i;
            InterviewQuestion q = contract("CORE", "BASIC");
            if (i < 12) {
                for (var importance : InterviewQuestion.Importance.values())
                    for (var difficulty : InterviewQuestion.Difficulty.values())
                        if (policy.weightUnits(importance, difficulty) == units[i]) q = contract(importance.name(), difficulty.name());
            }
            history.add(new InterviewService.Turn(id, "原题" + i, "我的回答"));
            frozen.put(id, q);
            results.put(id, assessed(id, i < 12 ? scores[i] : 55));
            contexts.put(id, new InterviewService.Context(i < 12 ? InterviewService.Kind.MAIN : InterviewService.Kind.ALGORITHM,
                    null, i + 1, i < 12 ? null : Hot100Catalog.problems().get(0).overview()));
        }
        var fullPlan = new InterviewPlan("experience-v1", InterviewPlan.Mode.FULL, 12, 13, 6, 2,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.EXPERIENCED, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.ALGORITHM, "算法", "岗位要求"), List.of(),
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1"), "简历", "fp", List.of());
        var report = new InterviewReportAssembler().assemble(13, history, frozen, results, contexts,
                "COMPLETED", fullPlan, new InterviewSnapshot.Progress(12, 12, 12, 0, 0, 6));
        assertEquals(new BigDecimal("42.4"), report.overallScore());
        assertEquals(new BigDecimal("80.00"), report.turnFeedbacks().subList(0, 12).stream()
                .map(InterviewAgent.TurnFeedback::weightPercent).reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(new BigDecimal("20.00"), report.turnFeedbacks().get(12).weightPercent());
        var prioritized = new LinkedHashMap<String, InterviewAssessment>();
        results.forEach((id, assessment) -> prioritized.put(id, new InterviewAssessment(id,
                assessed(id, id.equals("q12") ? 70 : 50).dimensionScores(), List.of("我的回答"), List.of(),
                "亮点" + id, "改进" + id, "组织回答", "test-model")));
        var ranked = new InterviewReportAssembler().assemble(13, history, frozen, prioritized, contexts);
        assertEquals("改进q12", ranked.suggestions().get(0));
    }
    @Test void missingAssessmentKeepsKnownScoresButWithholdsOverall() {
        var report = new InterviewReportAssembler().assemble(5, turns, contracts,
                Map.of("q1", assessed("q1", 80), "q3", assessed("q3", 70)));
        assertNull(report.overallScore());
        assertEquals("PARTIAL", report.reportStatus());
        assertEquals("PENDING", report.turnFeedbacks().get(1).status());
        assertNull(report.turnFeedbacks().get(1).score());
        assertEquals(80, report.turnFeedbacks().get(0).score());
        assertEquals(3, report.answeredCount());
    }
    @Test void earlyFinishAndUnansweredQuestionAreClearlySeparated() {
        var partial = List.of(turns.get(0), new InterviewService.Turn("q2", "第二题", null));
        var report = new InterviewReportAssembler().assemble(5, partial, contracts, Map.of("q1", assessed("q1", 0)), Map.of(
                "q1", new InterviewService.Context(InterviewService.Kind.MAIN, null, 1, null),
                "q2", new InterviewService.Context(InterviewService.Kind.MAIN, null, 2, null),
                "q3", new InterviewService.Context(InterviewService.Kind.MAIN, null, 3, null)), "USER_ENDED");
        assertNull(report.overallScore());
        assertEquals("USER_ENDED", report.finishReason());
        assertTrue(report.overallSummary().contains("提前结束"));
        assertEquals("UNANSWERED", report.turnFeedbacks().get(1).status());
        assertNull(report.turnFeedbacks().get(1).score());
        assertEquals(new BigDecimal("48.00"), report.turnFeedbacks().get(1).weightPercent());
        assertEquals(new BigDecimal("32.00"), report.turnFeedbacks().get(0).weightPercent());
    }
    @Test void skippedQuestionIsDistinctFromUnansweredAndNeverCreatesCompositeScore() {
        var partial = List.of(new InterviewService.Turn("q1", "第一题", null),
                new InterviewService.Turn("q2", "第二题", null));
        var contexts = Map.of(
                "q1", new InterviewService.Context(InterviewService.Kind.MAIN, null, 1, null),
                "q2", new InterviewService.Context(InterviewService.Kind.MAIN, null, 2, null));
        var report = new InterviewReportAssembler().assemble(5, partial, contracts, Map.of(), contexts,
                "USER_ENDED", null, new InterviewSnapshot.Progress(5, 1, 0, 1, 0, 2), Set.of("q1"));
        assertNull(report.overallScore());
        assertEquals("SKIPPED", report.turnFeedbacks().get(0).status());
        assertEquals("UNANSWERED", report.turnFeedbacks().get(1).status());
        assertTrue(report.overallSummary().contains("已跳过 1 个题组"));
    }
    @Test void noAnswerAndUnavailableScoringNeverProduceDefaultNumbers() {
        var empty = new InterviewReportAssembler().assemble(3,
                List.of(new InterviewService.Turn("q1", "第一题", null)), contracts, Map.of());
        assertEquals("EMPTY", empty.reportStatus()); assertNull(empty.overallScore());
        var unavailable = new InterviewReportAssembler().assemble(3, turns, contracts, Map.of());
        assertEquals("UNAVAILABLE", unavailable.reportStatus()); assertNull(unavailable.overallScore());
        assertTrue(unavailable.strengths().isEmpty());
    }

    @Test void completedQuickPlanHasNoCompositeScoreAndReportsItsActualScope() {
        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 3, 3, 2, 1,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "", ""), List.of(),
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1"), "简历", "fp", List.of());
        var contexts = new LinkedHashMap<String, InterviewService.Context>();
        var assessments = new LinkedHashMap<String, InterviewAssessment>();
        var questions = new LinkedHashMap<String, InterviewQuestion>();
        for (int i = 0; i < turns.size(); i++) {
            String id = turns.get(i).questionId();
            contexts.put(id, new InterviewService.Context(InterviewService.Kind.MAIN, null, i + 1, null));
            questions.put(id, contracts.get(id));
            assessments.put(id, assessed(id, 80));
        }
        var report = new InterviewReportAssembler().assemble(3, turns, questions, assessments, contexts,
                "COMPLETED", plan, new InterviewSnapshot.Progress(3, 3, 3, 0, 0, 2));
        assertNull(report.overallScore());
        assertTrue(report.overallSummary().contains("快速练习"));
        assertFalse(report.overallSummary().contains("未完成主问题 9 道"));
    }
}
