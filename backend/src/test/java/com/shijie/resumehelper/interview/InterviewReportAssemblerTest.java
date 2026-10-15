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
        assertEquals(new BigDecimal("80.0"), report.overallScore());
        assertEquals("COMPLETE", report.reportStatus());
        assertEquals(List.of("q1", "q2", "q3"), report.turnFeedbacks().stream().map(InterviewAgent.TurnFeedback::questionId).toList());
        assertEquals(new BigDecimal("25.00"), report.turnFeedbacks().get(0).weightPercent());
        assertEquals("第一题", report.turnFeedbacks().get(0).question());
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
        var report = new InterviewReportAssembler().assemble(5, partial, contracts, Map.of("q1", assessed("q1", 0)));
        assertEquals(new BigDecimal("0.0"), report.overallScore());
        assertTrue(report.overallSummary().contains("提前结束"));
        assertEquals("UNANSWERED", report.turnFeedbacks().get(1).status());
        assertNull(report.turnFeedbacks().get(1).score());
        assertNull(report.turnFeedbacks().get(1).weightPercent());
        assertEquals(new BigDecimal("100.00"), report.turnFeedbacks().get(0).weightPercent());
    }
    @Test void noAnswerAndUnavailableScoringNeverProduceDefaultNumbers() {
        var empty = new InterviewReportAssembler().assemble(3,
                List.of(new InterviewService.Turn("q1", "第一题", null)), contracts, Map.of());
        assertEquals("EMPTY", empty.reportStatus()); assertNull(empty.overallScore());
        var unavailable = new InterviewReportAssembler().assemble(3, turns, contracts, Map.of());
        assertEquals("UNAVAILABLE", unavailable.reportStatus()); assertNull(unavailable.overallScore());
        assertTrue(unavailable.strengths().isEmpty());
    }
}
