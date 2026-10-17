package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.shijie.resumehelper.interview.InterviewTestFixtures.question;
import static org.junit.jupiter.api.Assertions.*;

class InterviewGenerationRunnerTest {
    @Test
    void rejectedSubmissionBecomesFailedInsteadOfStayingQueued() {
        var service = new InterviewService(new InterviewAgent());
        var coordinator = new InterviewGenerationCoordinator(service);
        AiClient ai = (system, prompt) -> question("请介绍你负责的项目？");
        var session = service.start("alice", "mat-1", "Java 后端项目经验", "Java 后端", 13, ai);
        service.saveAnswer("alice", session.sessionId(), new InterviewService.Submission(
                session.currentQuestion().questionId(), "submission-1", "负责缓存设计和监控"));
        var waiting = service.get("alice", session.sessionId());
        var claim = coordinator.claim("alice", session.sessionId(), "req-rejected",
                InterviewSnapshot.GenerationKind.NEXT, waiting.revision());
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        var runner = new InterviewGenerationRunner(service, coordinator, executor);
        runner.start(claim, ai);
        assertEquals(InterviewSnapshot.GenerationState.FAILED,
                service.get("alice", session.sessionId()).generation().state());
        runner.close();
    }

    @Test
    void blockedModelDoesNotHoldSessionLockAndDuplicateStartRunsOnce() throws Exception {
        var service = new InterviewService(new InterviewAgent());
        var coordinator = new InterviewGenerationCoordinator(service);
        var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2));
        var runner = new InterviewGenerationRunner(service, coordinator, executor);
        var enteredModel = new CountDownLatch(1);
        var releaseModel = new CountDownLatch(1);
        var calls = new AtomicInteger();
        AiClient ai = (system, prompt) -> {
            calls.incrementAndGet();
            enteredModel.countDown();
            try { assertTrue(releaseModel.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            return question("请介绍你负责的项目？");
        };
        AiClient opening = (system, prompt) -> question("请介绍你负责的项目？");
        var started = service.start("alice", "mat-1", "Java 后端项目经验", "Java 后端", 13, opening);
        service.saveAnswer("alice", started.sessionId(), new InterviewService.Submission(
                started.currentQuestion().questionId(), "submission-1", "负责缓存设计和监控"));
        var waiting = service.get("alice", started.sessionId());
        var claim = coordinator.claim("alice", started.sessionId(), "req-1",
                InterviewSnapshot.GenerationKind.NEXT, waiting.revision());
        try {
            runner.start(claim, ai);
            assertTrue(enteredModel.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> service.get("alice", started.sessionId()));
            runner.start(claim, ai);
            assertEquals(1, calls.get());
        } finally {
            releaseModel.countDown();
            runner.close();
        }
    }
}
