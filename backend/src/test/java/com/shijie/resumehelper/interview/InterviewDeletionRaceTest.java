package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InterviewDeletionRaceTest {
    @Test
    void cancellingDeletedSessionDropsItsQueuedCallbackWithoutAffectingOtherRounds() {
        var tasks = new SessionTaskRegistry();
        var deletedCallback = new AtomicReference<Runnable>();
        var retainedCallback = new AtomicReference<Runnable>();
        var deletedRan = new AtomicBoolean();
        var retainedRan = new AtomicBoolean();
        tasks.submit("deleted", deletedCallback::set, () -> deletedRan.set(true));
        tasks.submit("retained", retainedCallback::set, () -> retainedRan.set(true));

        tasks.cancel("deleted");
        deletedCallback.get().run();
        retainedCallback.get().run();

        assertFalse(deletedRan.get());
        assertTrue(retainedRan.get());
    }

    @Test
    void cancellingDeletedSessionInterruptsItsRunningTask() throws Exception {
        var tasks = new SessionTaskRegistry();
        var executor = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try {
            tasks.submit("deleted", executor, () -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException cancelled) { interrupted.countDown(); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            tasks.cancel("deleted");
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void generationFailureCallbackCannotRestoreDeletedSession() {
        var service = service();
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");
        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 2, 2, 1, 1,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "", ""),
                List.of(new InterviewPlan.Coverage("impact", "项目效果")), source, "简历", "fp", List.of());
        var session = service.startAsync("alice", new InterviewSourceResolver.ResolvedSource(source, "简历", "正文", "fp"), plan, "delete-generation");
        var generation = session.generation();
        var claim = new InterviewGenerationCoordinator.GenerationClaim("alice", session.sessionId(),
                generation.generationId(), generation.requestId(), generation.kind(), session.revision());

        service.delete("alice", session.sessionId());
        var error = assertThrows(InterviewException.class, () ->
                new InterviewGenerationCoordinator(service).commitFailure(claim,
                        new InterviewFailure(InterviewFailure.Code.NETWORK, true, "网络错误", null)));

        assertEquals(404, error.status());
    }

    private static InterviewService service() {
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan, AiClient ai, int timeout) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
            @Override public Proposal preparePlannedNext(String resume, String role, List<InterviewService.Turn> history,
                    int total, int nextIndex, boolean allowFollowUp, InterviewPlan plan, AiClient ai, int timeout) {
                return new Proposal(InterviewTestFixtures.contract("CORE", "INTERMEDIATE"), false, plan.coverage().get(0).id());
            }
        };
        return new InterviewService(agent, null, Clock.systemUTC());
    }
}
