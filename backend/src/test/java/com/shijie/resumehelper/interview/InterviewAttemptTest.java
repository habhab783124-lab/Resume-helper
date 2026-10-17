package com.shijie.resumehelper.interview;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InterviewAttemptTest {
    private static final com.shijie.resumehelper.common.ai.AiClient AI = (system, prompt) -> "unused";
    @TempDir Path dir;

    @Test void attemptsRequireClosedQuickGroupOrFinishedSessionAndAreCappedAndIdempotent() {
        var fixture = fixture(InterviewPlan.Mode.QUICK, null, new InterviewAgent());
        var saved = fixture.interviews.saveAnswer("owner", fixture.sessionId,
                new InterviewService.Submission(fixture.questionId, "root-answer", "首答"));
        assertThrows(InterviewException.class, () -> fixture.interviews.submitAttempt("owner", fixture.sessionId,
                fixture.questionId, "attempt-1", "重答"));
        fixture.interviews.queuePracticeFeedback("owner", fixture.sessionId, fixture.questionId,
                "close-root", saved.revision());
        var first = fixture.interviews.submitAttempt("owner", fixture.sessionId, fixture.questionId,
                "attempt-1", "改进后的回答").attempt();
        var repeated = fixture.interviews.submitAttempt("owner", fixture.sessionId, fixture.questionId,
                "attempt-1", "改进后的回答").attempt();
        assertEquals(first.attemptId(), repeated.attemptId());
        for (int version = 2; version <= 5; version++)
            fixture.interviews.submitAttempt("owner", fixture.sessionId, fixture.questionId,
                    "attempt-" + version, "重答版本 " + version);
        assertEquals(5, fixture.interviews.getAttempt("owner", fixture.sessionId,
                fixture.interviews.submitAttempt("owner", fixture.sessionId, fixture.questionId, "attempt-5", "重答版本 5").attempt().attemptId()).version());
        assertThrows(InterviewException.class, () -> fixture.interviews.submitAttempt("owner", fixture.sessionId,
                fixture.questionId, "attempt-6", "第六版"));
        assertThrows(InterviewException.class, () -> fixture.interviews.submitAttempt("other", fixture.sessionId,
                fixture.questionId, "other-owner", "越权重答"));
        assertThrows(IllegalArgumentException.class, () -> fixture.interviews.submitAttempt("owner", fixture.sessionId,
                fixture.questionId, "empty", "  "));
        assertThrows(IllegalArgumentException.class, () -> fixture.interviews.submitAttempt("owner", fixture.sessionId,
                fixture.questionId, "long", "x".repeat(5001)));
    }

    @Test void fullActiveSessionRejectsAttemptSubmission() {
        var fixture = fixture(InterviewPlan.Mode.FULL, null, new InterviewAgent());
        fixture.interviews.saveAnswer("owner", fixture.sessionId,
                new InterviewService.Submission(fixture.questionId, "root-answer", "首答"));
        assertThrows(InterviewException.class, () -> fixture.interviews.submitAttempt("owner", fixture.sessionId,
                fixture.questionId, "attempt-1", "重答"));
    }

    @Test void missingModelCredentialsFailScoringAfterTheNewAnswerHasBeenPersisted() {
        var fixture = fixture(InterviewPlan.Mode.QUICK, null, new InterviewAgent());
        var saved = fixture.interviews.saveAnswer("owner", fixture.sessionId,
                new InterviewService.Submission(fixture.questionId, "root-answer", "首答"));
        fixture.interviews.queuePracticeFeedback("owner", fixture.sessionId, fixture.questionId, "close", saved.revision());
        var practice = new InterviewPracticeService(fixture.interviews, new InterviewAgent());
        var attempt = practice.submitAttempt("owner", fixture.sessionId, fixture.questionId,
                "missing-ai", "模型暂不可用时也要保存这份重答", null);
        assertEquals(InterviewAttempt.Status.FAILED, attempt.status());
        assertEquals("模型暂不可用时也要保存这份重答", attempt.answer());
        assertEquals("首答", fixture.interviews.get("owner", fixture.sessionId).turns().get(0).answer());
        practice.close();
    }

    @Test void scoringFailureKeepsAttemptAndRetryScoresSameVersionWithoutChangingFirstAnswerOrReportScore() throws Exception {
        var calls = new AtomicInteger();
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
            @Override public Map<String, InterviewAssessment> assessAnswers(String resume, String role,
                    List<AssessmentInput> inputs, List<InterviewService.Turn> history,
                    com.shijie.resumehelper.common.ai.AiClient ai) {
                if (calls.incrementAndGet() == 1) throw new RuntimeException("model unavailable");
                var input = inputs.get(0);
                return Map.of(input.turn().questionId(), assessment(input.turn().questionId(), input.turn().answer(), 75));
            }
        };
        var fixture = fixture(InterviewPlan.Mode.QUICK, null, agent);
        String originalAnswer = "原答案里的真实证据";
        var saved = fixture.interviews.saveAnswer("owner", fixture.sessionId,
                new InterviewService.Submission(fixture.questionId, "root-answer", originalAnswer));
        var feedback = fixture.interviews.queuePracticeFeedback("owner", fixture.sessionId, fixture.questionId,
                "close-root", saved.revision());
        fixture.interviews.startPracticeFeedback("owner", fixture.sessionId, fixture.questionId, "close-root");
        fixture.interviews.completePracticeFeedback("owner", fixture.sessionId, fixture.questionId, "close-root",
                assessment(fixture.questionId, originalAnswer, 50));
        var practice = new InterviewPracticeService(fixture.interviews, agent);
        try {
            var attempt = practice.submitAttempt("owner", fixture.sessionId, fixture.questionId,
                    "attempt-1", "新的回答证据和更清楚的验证过程", AI);
            attempt = awaitStatus(fixture, attempt.attemptId(), InterviewAttempt.Status.FAILED);
            String attemptId = attempt.attemptId();
            String failedOperationId = attempt.operationId();
            var retry = practice.retryAttempt("owner", fixture.sessionId, attemptId, "retry-1", AI);
            assertEquals(attemptId, retry.attemptId());
            assertEquals(1, retry.version());
            retry = awaitStatus(fixture, attemptId, InterviewAttempt.Status.COMPLETE);
            assertEquals(25, retry.comparison().scoreChange());
            assertTrue(retry.comparison().trainingReference());
            fixture.interviews.completeAttempt("owner", fixture.sessionId, attemptId, failedOperationId,
                    assessment(fixture.questionId, "伪造迟到评分", 100));
            assertEquals(75, new InterviewScoringPolicy().questionScore(
                    fixture.interviews.getAttempt("owner", fixture.sessionId, attemptId).feedback().dimensionScores()));
            var duplicateRetry = fixture.interviews.retryAttempt("owner", fixture.sessionId, attemptId, "retry-1");
            assertFalse(duplicateRetry.enqueue());
            assertEquals(retry.operationId(), duplicateRetry.attempt().operationId());
            assertEquals(originalAnswer, fixture.interviews.get("owner", fixture.sessionId).turns().get(0).answer());
            fixture.interviews.finish("owner", fixture.sessionId, "finish-after-attempt", null);
            assertEquals(50, new InterviewScoringPolicy().questionScore(
                    fixture.interviews.reportSnapshot("owner", fixture.sessionId).turnFeedbacks().get(0).dimensionScores()));
            assertEquals("新的回答证据和更清楚的验证过程", practice.getAttempt("owner", fixture.sessionId, attemptId).answer());
        } finally { practice.close(); }
    }

    @Test void pendingAttemptSurvivesRestartAsFailedWithAnswerAvailableForRetry() {
        var store = new FileSystemInterviewStore(dir, new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        var fixture = fixture(InterviewPlan.Mode.QUICK, store, new InterviewAgent());
        var saved = fixture.interviews.saveAnswer("owner", fixture.sessionId,
                new InterviewService.Submission(fixture.questionId, "root-answer", "首答"));
        fixture.interviews.queuePracticeFeedback("owner", fixture.sessionId, fixture.questionId, "close", saved.revision());
        var attempt = fixture.interviews.submitAttempt("owner", fixture.sessionId, fixture.questionId,
                "attempt-1", "重答内容").attempt();
        var restarted = new InterviewService(new InterviewAgent(), store, Clock.systemUTC());
        var restored = restarted.getAttempt("owner", fixture.sessionId, attempt.attemptId());
        assertEquals(InterviewAttempt.Status.FAILED, restored.status());
        assertEquals("重答内容", restored.answer());
        assertTrue(restored.error().contains("重启"));
        assertEquals(attempt.version(), restored.version());
    }

    @Test void expiredAttemptCallbackCannotRecreateItsSession() {
        var fixture = fixture(InterviewPlan.Mode.QUICK, null, new InterviewAgent());
        var saved = fixture.interviews.saveAnswer("owner", fixture.sessionId,
                new InterviewService.Submission(fixture.questionId, "root-answer", "首答"));
        fixture.interviews.queuePracticeFeedback("owner", fixture.sessionId, fixture.questionId, "close", saved.revision());
        var attempt = fixture.interviews.submitAttempt("owner", fixture.sessionId, fixture.questionId,
                "attempt-expire", "待评分的重答").attempt();
        fixture.clock.advance(java.time.Duration.ofDays(31));
        assertThrows(InterviewException.class, () -> fixture.interviews.completeAttempt("owner", fixture.sessionId,
                attempt.attemptId(), attempt.operationId(), assessment(fixture.questionId, attempt.answer(), 70)));
        assertThrows(InterviewException.class, () -> fixture.interviews.getAttempt("owner", fixture.sessionId, attempt.attemptId()));
    }

    private static InterviewAttempt awaitStatus(Fixture fixture, String attemptId, InterviewAttempt.Status status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        InterviewAttempt attempt = fixture.interviews.getAttempt("owner", fixture.sessionId, attemptId);
        while (attempt.status() != status && System.nanoTime() < deadline) {
            Thread.sleep(10); attempt = fixture.interviews.getAttempt("owner", fixture.sessionId, attemptId);
        }
        assertEquals(status, attempt.status());
        return attempt;
    }

    private static InterviewAssessment assessment(String id, String answer, int score) {
        var dimensions = new EnumMap<InterviewQuestion.Dimension, Integer>(InterviewQuestion.Dimension.class);
        int left = score;
        for (var dimension : InterviewQuestion.Dimension.values()) {
            int value = Math.min(20, left / 5 * 5); dimensions.put(dimension, value); left -= value;
        }
        return new InterviewAssessment(id, dimensions, List.of(answer), List.of("补充真实统计周期"),
                "回答提供了可核对的事实", "补充真实统计周期", "目标、行动、证据、结果", "test-model");
    }

    private static Fixture fixture(InterviewPlan.Mode mode, InterviewStore store, InterviewAgent agent) {
        InterviewAgent openingAgent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
        };
        if (agent != null && agent.getClass() != InterviewAgent.class) openingAgent = agent;
        int count = mode == InterviewPlan.Mode.QUICK ? 3 : 12;
        var plan = new InterviewPlan("experience-v1", mode, count, mode == InterviewPlan.Mode.QUICK ? 3 : 13,
                2, 1, InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(mode == InterviewPlan.Mode.QUICK ? InterviewPlan.SpecialtyKind.NONE : InterviewPlan.SpecialtyKind.ALGORITHM,
                        mode == InterviewPlan.Mode.QUICK ? "" : "算法", "岗位专项题用于检查相关能力"),
                List.of(new InterviewPlan.Coverage("impact", "项目效果")),
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1"), "简历", "fp", List.of());
        var source = new InterviewSourceResolver.ResolvedSource(plan.source(), "简历", "正文", "fp");
        var clock = new MutableClock();
        var interviews = new InterviewService(openingAgent, store, clock);
        var session = interviews.startAsync("owner", source, plan, "start-" + mode + "-" + java.util.UUID.randomUUID());
        String generationId = interviews.claimGeneration("owner", session.sessionId(), "opening-" + mode,
                InterviewSnapshot.GenerationKind.OPENING, session.revision());
        var claim = new InterviewGenerationCoordinator.GenerationClaim("owner", session.sessionId(), generationId,
                "opening-" + mode, InterviewSnapshot.GenerationKind.OPENING, session.revision());
        var opened = interviews.commitGenerationSuccess("owner", session.sessionId(), generationId,
                claim.expectedRevision(), interviews.generate(claim, AI));
        return new Fixture(interviews, opened.sessionId(), opened.currentQuestion().questionId(), clock);
    }

    private record Fixture(InterviewService interviews, String sessionId, String questionId, MutableClock clock) {}

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-17T10:00:00Z");
        void advance(java.time.Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
