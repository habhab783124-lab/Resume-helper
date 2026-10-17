package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.shijie.resumehelper.interview.InterviewTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-interface recovery checks using real snapshots and a controlled local model. */
class InterviewReliabilityIntegrationTest {
    @TempDir Path directory;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T08:00:00Z"), ZoneOffset.UTC);

    private InterviewStore store() {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new FileSystemInterviewStore(directory, mapper);
    }

    private InterviewService service(InterviewStore store) {
        return new InterviewService(new InterviewAgent(), store, CLOCK);
    }

    private AiClient model() {
        return (system, prompt) -> prompt.contains("selectedAlgorithm")
                ? algorithmFromPrompt(prompt) : question("请说明你如何验证缓存优化效果？");
    }

    @Test
    void finishFencesLateGenerationAndRestartsQueuedScoringAsRetryable() {
        var store = store();
        var first = service(store);
        var opened = first.start("alice", "material-1", "Java 项目与 Redis 缓存", "Java 后端", 13, model());
        var answer = new InterviewService.Submission(opened.currentQuestion().questionId(), "submission-1", "用监控验证了缓存命中率。");
        var saved = first.saveAnswer("alice", opened.sessionId(), answer);
        String generationId = first.claimGeneration("alice", opened.sessionId(), "next-1",
                InterviewSnapshot.GenerationKind.NEXT, saved.revision());

        var finished = first.finish("alice", opened.sessionId(), "finish-1", null);
        assertEquals("USER_ENDED", finished.finishReason());
        assertThrows(InterviewException.class, () -> first.commitGenerationSuccess("alice", opened.sessionId(),
                generationId, saved.revision(), new InterviewService.GenerationResult(
                        contract("CORE", "INTERMEDIATE"), InterviewService.Kind.MAIN, null, 2, null, null, null)));

        var queued = first.queueReport("alice", opened.sessionId(), "report-1", false);
        assertTrue(queued.enqueue());
        assertEquals("QUEUED", queued.report().scoringState());

        var restarted = service(store);
        var restored = restarted.get("alice", opened.sessionId());
        assertEquals("FINISHED", restored.status());
        assertEquals("USER_ENDED", restored.finishReason());
        assertEquals("用监控验证了缓存命中率。", restored.turns().get(0).answer());
        var report = restarted.reportSnapshot("alice", opened.sessionId());
        assertEquals("FAILED", report.scoringState(), "orphaned work must become retryable after restart");
        var retry = restarted.queueReport("alice", opened.sessionId(), "report-retry-1", true);
        assertTrue(retry.enqueue());
        assertTrue(restarted.startReportScoring("alice", opened.sessionId(), retry.operationId()));
        restarted.delete("alice", opened.sessionId());
        assertEquals(404, assertThrows(InterviewException.class,
                () -> restarted.completeReportScoring("alice", opened.sessionId(), retry.operationId())).status());
        assertTrue(store.find("alice", opened.sessionId()).isEmpty(), "delete after restart/retry must tombstone the persisted session");
        assertTrue(restarted.history("alice", 20, null).items().stream()
                .noneMatch(item -> item.id().equals(opened.sessionId())));
        assertEquals(404, assertThrows(InterviewException.class,
                () -> restarted.get("alice", opened.sessionId())).status());
        assertThrows(InterviewException.class, () -> restarted.get("bob", opened.sessionId()));
    }

    @Test
    void startRequestReplayAfterServiceRestartReturnsTheSameOpening() {
        var store = store();
        var first = service(store);
        var one = first.start("alice", "material-1", "Java 项目与 Redis 缓存", "Java 后端", 13, model());
        // This verifies replay through the durable start request index independently of in-memory state.
        var asyncOne = first.startAsync("alice", "material-2", "另一份简历", "运营", "start-request-1");
        var restarted = service(store);
        var replay = restarted.startAsync("alice", "material-2", "另一份简历", "运营", "start-request-1");
        assertEquals(asyncOne.sessionId(), replay.sessionId());
        assertNotEquals(one.sessionId(), replay.sessionId());
    }

    @Test
    void deletingSessionRejectsLateReAnswerAssessmentCallback() {
        var store = store();
        var service = service(store);
        var opened = service.start("alice", "material-attempt", "Java 项目经验", "Java 后端", 13, model());
        String questionId = opened.currentQuestion().questionId();
        var saved = service.saveAnswer("alice", opened.sessionId(),
                new InterviewService.Submission(questionId, "attempt-first-answer", "先保存的回答。"));
        service.finish("alice", opened.sessionId(), "attempt-finish", null);
        var attempt = service.submitAttempt("alice", opened.sessionId(), questionId,
                "attempt-request", "改进后的重答。");
        assertTrue(service.startAttempt("alice", opened.sessionId(), attempt.attempt().attemptId(),
                attempt.attempt().operationId()));

        service.delete("alice", opened.sessionId());

        assertEquals(404, assertThrows(InterviewException.class, () -> service.completeAttempt("alice",
                opened.sessionId(), attempt.attempt().attemptId(), attempt.attempt().operationId(),
                assessed(questionId, 15))).status());
        assertEquals("ACTIVE", saved.status());
        assertTrue(store.find("alice", opened.sessionId()).isEmpty());
    }

    @Test
    void deletingSessionRejectsLateReviewSummaryCallback() {
        var store = store();
        var service = service(store);
        var opened = service.start("alice", "material-review", "Java 项目经验", "Java 后端", 13, model());
        service.finish("alice", opened.sessionId(), "review-finish", null);
        var report = service.queueReport("alice", opened.sessionId(), "review-report", false);
        assertEquals("SUCCEEDED", report.report().scoringState());
        var review = service.queueReview("alice", opened.sessionId(), "review-operation", false);
        assertTrue(review.enqueue());
        assertTrue(service.startReview("alice", opened.sessionId(), review.operationId()));

        service.delete("alice", opened.sessionId());

        assertEquals(404, assertThrows(InterviewException.class,
                () -> service.completeReview("alice", opened.sessionId(), review.operationId(), List.of())).status());
        assertTrue(store.find("alice", opened.sessionId()).isEmpty());
    }

    @Test
    void quickSkipAndFeedbackRemainOwnerScopedAcrossDeleteAndLateCallback() {
        var store = store();
        var openingCalls = new AtomicInteger();
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    AiClient ai, int timeout) {
                if (openingCalls.getAndIncrement() == 0)
                    throw new InterviewException(502, "temporary network failure",
                            new InterviewFailure(InterviewFailure.Code.NETWORK, true,
                                    "temporary network failure", 1L));
                return contract("CORE", "INTERMEDIATE");
            }
            @Override public Proposal preparePlannedNext(String resume, String role, List<InterviewService.Turn> history,
                    int total, int nextIndex, boolean allowFollowUp, InterviewPlan plan, AiClient ai, int timeout) {
                return new Proposal(contract("CORE", "INTERMEDIATE"), false, plan.coverage().get(0).id());
            }
        };
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "quick-material");
        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 3, 3, 2, 1,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "", ""),
                List.of(new InterviewPlan.Coverage("impact", "项目效果")), source, "简历", "quick-fp", List.of());
        var retryProperties = new InterviewProperties();
        retryProperties.setMaxRetries(1);
        retryProperties.setRetryDelaysMs(List.of(1L));
        var service = new InterviewService(agent, store, CLOCK, retryProperties, ignored -> { });
        var opening = service.startAsync("alice", new InterviewSourceResolver.ResolvedSource(source, "简历", "正文", "quick-fp"), plan, "quick-delete-flow");
        var openingGeneration = opening.generation();
        var openingClaim = new InterviewGenerationCoordinator.GenerationClaim("alice", opening.sessionId(),
                openingGeneration.generationId(), openingGeneration.requestId(), openingGeneration.kind(), opening.revision());
        var quick = service.commitGenerationSuccess("alice", opening.sessionId(), openingGeneration.generationId(),
                opening.revision(), service.generate(openingClaim, model()));
        assertEquals(2, openingCalls.get(), "a one-time network fault is retried once in the QUICK flow");

        var skipped = service.closeRoot("alice", quick.sessionId(), quick.currentQuestion().questionId(),
                "quick-skip-first", quick.revision());
        assertEquals(skipped.revision(), service.closeRoot("alice", quick.sessionId(),
                quick.currentQuestion().questionId(), "quick-skip-first", quick.revision()).revision());
        assertEquals(1, skipped.progress().skippedRoots());
        String nextRequest = "quick-next-after-skip";
        String nextId = service.claimGeneration("alice", quick.sessionId(), nextRequest,
                InterviewSnapshot.GenerationKind.NEXT, skipped.revision());
        var nextClaim = new InterviewGenerationCoordinator.GenerationClaim("alice", quick.sessionId(), nextId,
                nextRequest, InterviewSnapshot.GenerationKind.NEXT, skipped.revision());
        var next = service.commitGenerationSuccess("alice", quick.sessionId(), nextId, skipped.revision(),
                service.generate(nextClaim, model()));
        String nextQuestion = next.currentQuestion().questionId();
        var answered = service.saveAnswer("alice", next.sessionId(),
                new InterviewService.Submission(nextQuestion, "quick-next-answer", "用对照指标验证效果。"));
        var feedback = service.queuePracticeFeedback("alice", next.sessionId(), nextQuestion,
                "quick-feedback", answered.revision());
        assertTrue(feedback.enqueue());
        assertTrue(service.startPracticeFeedback("alice", next.sessionId(), nextQuestion, "quick-feedback"));

        service.delete("alice", next.sessionId());

        assertEquals(404, assertThrows(InterviewException.class, () -> service.completePracticeFeedback("alice",
                next.sessionId(), nextQuestion, "quick-feedback", assessed(nextQuestion, 15))).status());
        assertTrue(store.find("alice", next.sessionId()).isEmpty());
        assertTrue(service.history("alice", 20, null).items().stream()
                .noneMatch(item -> item.id().equals(next.sessionId())));

        var fullSource = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "full-material");
        var fullPlan = new InterviewPlan("experience-v1", InterviewPlan.Mode.FULL, 12, 13, 6, 2,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.CASE_STUDY, "产品案例", "岗位专项案例"),
                List.of(new InterviewPlan.Coverage("impact", "项目效果")), fullSource, "简历", "full-fp", List.of());
        var fullService = new InterviewService(agent, store, CLOCK, retryProperties, ignored -> { });
        var fullOpening = fullService.startAsync("alice",
                new InterviewSourceResolver.ResolvedSource(fullSource, "简历", "正文", "full-fp"),
                fullPlan, "full-restart-flow");
        var fullGeneration = fullOpening.generation();
        var fullClaim = new InterviewGenerationCoordinator.GenerationClaim("alice", fullOpening.sessionId(),
                fullGeneration.generationId(), fullGeneration.requestId(), fullGeneration.kind(), fullOpening.revision());
        var fullSession = fullService.commitGenerationSuccess("alice", fullOpening.sessionId(),
                fullGeneration.generationId(), fullOpening.revision(), fullService.generate(fullClaim, model()));
        assertEquals(InterviewPlan.Mode.FULL, fullSession.plan().mode());
        assertEquals(12, fullSession.plan().mainCount());
        assertEquals(13, fullSession.plan().plannedCount());
        String fullQuestion = fullSession.currentQuestion().questionId();
        fullService.saveAnswer("alice", fullSession.sessionId(),
                new InterviewService.Submission(fullQuestion, "full-first-answer", "先答题后再开启独立重答。"));
        fullService.finish("alice", fullSession.sessionId(), "full-finish", null);
        var fullRestart = new InterviewService(agent, store, CLOCK, retryProperties, ignored -> { });
        assertEquals(InterviewPlan.Mode.FULL, fullRestart.get("alice", fullSession.sessionId()).plan().mode());
        var fullAttempt = fullRestart.submitAttempt("alice", fullSession.sessionId(), fullQuestion,
                "full-attempt-request", "重新组织后的回答。");
        assertTrue(fullRestart.startAttempt("alice", fullSession.sessionId(), fullAttempt.attempt().attemptId(),
                fullAttempt.attempt().operationId()));
        fullRestart.delete("alice", fullSession.sessionId());
        assertEquals(404, assertThrows(InterviewException.class, () -> fullRestart.completeAttempt("alice",
                fullSession.sessionId(), fullAttempt.attempt().attemptId(), fullAttempt.attempt().operationId(),
                assessed(fullQuestion, 15))).status());
    }

    private InterviewService service(InterviewStore store, InterviewAgent agent) {
        return new InterviewService(agent, store, CLOCK);
    }
}
