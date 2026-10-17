package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class InterviewSkipTest {
    @Test void skipIsIdempotentByRequestIdAndCannotBeReusedForAnotherQuestion() {
        var service = service(false); var session = open(service, plan(2), "skip-start-1");
        String root1 = session.currentQuestion().questionId();
        var skipped = service.closeRoot("alice", session.sessionId(), root1, "skip-request-1", session.revision());
        var duplicate = service.closeRoot("alice", session.sessionId(), root1, "skip-request-1", session.revision());
        assertEquals(skipped.revision(), duplicate.revision());
        session = advance(service, skipped, "after-skip-1"); String root2 = session.currentQuestion().questionId(); var current = session;
        var reused = assertThrows(InterviewException.class, () -> service.closeRoot("alice", current.sessionId(), root2, "skip-request-1", current.revision()));
        assertEquals(409, reused.status());
        var late = assertThrows(InterviewException.class, () -> service.saveAnswer("alice", current.sessionId(), new InterviewService.Submission(root1, "late-answer", "回答")));
        assertEquals(409, late.status());
    }

    @Test void skipRejectsAnotherOwner() {
        var service = service(false); var session = open(service, plan(2), "skip-owner-start");
        var error = assertThrows(InterviewException.class, () -> service.closeRoot("mallory", session.sessionId(),
                session.currentQuestion().questionId(), "skip-owner-request", session.revision()));
        assertEquals(404, error.status());
    }
    @Test void answeredRootCannotBeSkipped() {
        var service = service(false); var session = open(service, plan(2), "skip-start-2");
        String rootId = session.currentQuestion().questionId();
        session = service.saveAnswer("alice", session.sessionId(), new InterviewService.Submission(rootId, "answer-1", "已回答")); var answered = session;
        var error = assertThrows(InterviewException.class, () -> service.closeRoot("alice", answered.sessionId(), rootId, "skip-answered", answered.revision()));
        assertEquals(409, error.status());
    }
    @Test void concurrentSaveAndSkipCommitAtMostOneOutcome() throws Exception {
        var service = service(false); var session = open(service, plan(2), "skip-race-start");
        String rootId = session.currentQuestion().questionId();
        var gate = new CountDownLatch(1);
        var saveResult = new AtomicReference<InterviewService.Session>();
        var skipResult = new AtomicReference<InterviewService.Session>();
        Thread save = new Thread(() -> { await(gate); try {
            saveResult.set(service.saveAnswer("alice", session.sessionId(), new InterviewService.Submission(rootId, "race-answer", "回答")));
        } catch (InterviewException expectedConflict) { } });
        Thread skip = new Thread(() -> { await(gate); try {
            skipResult.set(service.closeRoot("alice", session.sessionId(), rootId, "race-skip", session.revision()));
        } catch (InterviewException expectedConflict) { } });
        save.start(); skip.start(); gate.countDown(); save.join(); skip.join();
        assertNotNull(saveResult.get() == null ? skipResult.get() : saveResult.get());
        var finalState = service.get("alice", session.sessionId());
        var turn = finalState.turns().stream().filter(t -> t.questionId().equals(rootId)).findFirst().orElseThrow();
        if (saveResult.get() != null) {
            assertEquals("回答", turn.answer()); assertEquals(1, finalState.progress().answeredRoots());
            assertEquals(0, finalState.progress().skippedRoots());
        } else {
            assertNull(turn.answer()); assertEquals(0, finalState.progress().answeredRoots());
            assertEquals(1, finalState.progress().skippedRoots());
        }
    }
    @Test void concurrentFinishAndSkipCannotLeaveAnActiveSkippedQuestion() throws Exception {
        var service = service(false); var session = open(service, plan(2), "skip-finish-start");
        String rootId = session.currentQuestion().questionId();
        var gate = new CountDownLatch(1);
        var skipResult = new AtomicReference<InterviewService.Session>();
        var finishResult = new AtomicReference<InterviewService.Session>();
        Thread skip = new Thread(() -> { await(gate); try {
            skipResult.set(service.closeRoot("alice", session.sessionId(), rootId, "race-finish-skip", session.revision()));
        } catch (InterviewException expectedConflict) { } });
        Thread finish = new Thread(() -> { await(gate); try {
            finishResult.set(service.finish("alice", session.sessionId(), "race-finish", null));
        } catch (InterviewException expectedConflict) { } });
        skip.start(); finish.start(); gate.countDown(); skip.join(); finish.join();
        assertNotNull(finishResult.get());
        var finalState = service.get("alice", session.sessionId());
        assertEquals("FINISHED", finalState.status());
        assertEquals(skipResult.get() == null ? 0 : 1, finalState.progress().skippedRoots());
        assertNull(finalState.currentQuestion());
    }
    private static void await(CountDownLatch gate) {
        try { gate.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
    }
    @Test void skippingFollowUpKeepsParentAnswerAndAdvancesAsOneResolvedRoot() {
        var service = service(true); var session = open(service, plan(2), "skip-start-3");
        String rootId = session.currentQuestion().questionId();
        session = service.saveAnswer("alice", session.sessionId(), new InterviewService.Submission(rootId, "answer-root", "保留的主回答"));
        session = generateNext(service, session, "make-follow-up");
        assertEquals(InterviewService.Kind.FOLLOW_UP, session.questionMetadata().get(session.currentQuestion().questionId()).kind());
        String followUpId = session.currentQuestion().questionId();
        session = service.closeRoot("alice", session.sessionId(), followUpId, "skip-follow-up", session.revision());
        assertEquals("保留的主回答", session.turns().get(0).answer());
        assertEquals(1, session.progress().answeredRoots()); assertEquals(0, session.progress().skippedRoots());
        session = generateNext(service, session, "advance-after-follow-up-skip");
        assertEquals(2, session.questionMetadata().get(session.currentQuestion().questionId()).mainQuestionIndex());
    }
    private static InterviewService service(boolean followUp) {
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan, AiClient ai, int timeout) { return InterviewTestFixtures.contract("CORE", "INTERMEDIATE"); }
            @Override public Proposal preparePlannedNext(String resume, String role, List<InterviewService.Turn> history, int total, int nextIndex, boolean allowFollowUp, InterviewPlan plan, AiClient ai, int timeout) {
                return followUp && allowFollowUp ? new Proposal(InterviewTestFixtures.contract("CORE", "INTERMEDIATE"), true, null)
                        : new Proposal(InterviewTestFixtures.contract("CORE", "INTERMEDIATE"), false, plan.coverage().get(0).id());
            }
        };
        return new InterviewService(agent, null, Clock.systemUTC());
    }
    private static InterviewPlan plan(int count) {
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");
        return new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, count, count, 1, 1,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "", ""),
                List.of(new InterviewPlan.Coverage("impact", "项目效果")), source, "简历", "fp", List.of());
    }
    private static InterviewService.Session open(InterviewService service, InterviewPlan plan, String request) {
        var source = new InterviewSourceResolver.ResolvedSource(plan.source(), "简历", "正文", "fp");
        var session = service.startAsync("alice", source, plan, request); var generation = session.generation();
        var claim = new InterviewGenerationCoordinator.GenerationClaim("alice", session.sessionId(), generation.generationId(), generation.requestId(), generation.kind(), session.revision());
        return service.commitGenerationSuccess("alice", session.sessionId(), claim.generationId(), claim.expectedRevision(), service.generate(claim, (system, prompt) -> "unused"));
    }
    private static InterviewService.Session advance(InterviewService service, InterviewService.Session session, String request) { return generateNext(service, session, request); }
    private static InterviewService.Session generateNext(InterviewService service, InterviewService.Session session, String request) {
        String id = service.claimGeneration("alice", session.sessionId(), request, InterviewSnapshot.GenerationKind.NEXT, session.revision());
        var claim = new InterviewGenerationCoordinator.GenerationClaim("alice", session.sessionId(), id, request, InterviewSnapshot.GenerationKind.NEXT, session.revision());
        return service.commitGenerationSuccess("alice", session.sessionId(), id, session.revision(), service.generate(claim, (system, prompt) -> "unused"));
    }
}
