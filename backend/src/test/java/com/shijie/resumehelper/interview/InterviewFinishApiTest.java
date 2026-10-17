package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.shijie.resumehelper.interview.InterviewTestFixtures.question;
import static org.junit.jupiter.api.Assertions.*;

class InterviewFinishApiTest {
    @Test
    void finalAnswerAndFinishAreOneDurableRevisionAndDuplicateIsIdempotent() {
        var service = new InterviewService(new InterviewAgent());
        var session = service.start("alice", "mat", "resume", "backend", 13,
                (s, p) -> question("介绍项目？"));
        long revision = session.revision();
        var submission = new InterviewService.Submission(session.currentQuestion().questionId(),
                "finish-submission", "最后回答");

        var finished = service.finish("alice", session.sessionId(), "finish-request", submission);

        assertEquals("FINISHED", finished.status());
        assertEquals("USER_ENDED", finished.finishReason());
        assertEquals("最后回答", finished.turns().get(0).answer());
        assertNull(finished.currentQuestion());
        assertEquals(revision + 1, finished.revision());
        assertEquals(finished, service.finish("alice", session.sessionId(), "finish-request", submission));
    }

    @Test
    void alreadySavedFinalAnswerCanBeSubmittedAgainWhileFinishing() {
        var service = new InterviewService(new InterviewAgent());
        var session = service.start("alice", "mat", "resume", "backend", 13,
                (s, p) -> question("介绍项目？"));
        var submission = new InterviewService.Submission(session.currentQuestion().questionId(), "saved-sub", "已保存答案");
        var saved = service.saveAnswer("alice", session.sessionId(), submission);
        var finished = service.finish("alice", session.sessionId(), "finish-request", submission);
        assertEquals("FINISHED", finished.status());
        assertEquals(saved.turns().get(0).answer(), finished.turns().get(0).answer());
        assertEquals(saved.revision() + 1, finished.revision());
    }

    @Test
    void storageFailureDoesNotCommitAnswerOrEndSession() throws Exception {
        var temp = Files.createTempDirectory("finish-failure");
        var store = new FailingStore(new FileSystemInterviewStore(temp, new ObjectMapper().findAndRegisterModules()));
        var service = new InterviewService(new InterviewAgent(), store, Clock.systemUTC());
        var session = service.start("alice", "mat", "resume", "backend", 13,
                (s, p) -> question("介绍项目？"));
        store.failNext.set(true);
        var submission = new InterviewService.Submission(session.currentQuestion().questionId(), "finish-sub", "最后回答");
        assertThrows(InterviewException.class,
                () -> service.finish("alice", session.sessionId(), "finish-request", submission));
        var after = service.get("alice", session.sessionId());
        assertEquals("ACTIVE", after.status());
        assertEquals(session.revision(), after.revision());
        assertNull(after.turns().get(0).answer());
        assertNotNull(after.currentQuestion());
    }

    @Test
    void queuedOpeningCanEndWithZeroAnswers() {
        var service = new InterviewService(new InterviewAgent());
        var queued = service.startAsync("alice", "mat", "resume", "backend", "open-request");
        var finished = service.finish("alice", queued.sessionId(), "finish-request", null);
        assertEquals("FINISHED", finished.status());
        assertEquals("USER_ENDED", finished.finishReason());
        assertTrue(finished.turns().isEmpty());
        assertEquals(InterviewSnapshot.GenerationState.CANCELLED, finished.generation().state());
    }

    @Test
    void retryWaitCanBeFinishedWithoutStartingAnotherAttempt() throws Exception {
        var enteredWait = new CountDownLatch(1);
        var properties = new InterviewProperties();
        properties.setRetryDelaysMs(List.of(1L));
        var service = new InterviewService(new InterviewAgent(), null, Clock.systemUTC(), properties, delay -> {
            enteredWait.countDown();
            new CountDownLatch(1).await();
        });
        var coordinator = new InterviewGenerationCoordinator(service);
        var executor = Executors.newSingleThreadExecutor();
        var runner = new InterviewGenerationRunner(service, coordinator, executor);
        var session = service.startAsync("alice", "mat", "resume", "backend", "open-request");
        var claim = coordinator.claim("alice", session.sessionId(), "open-request",
                InterviewSnapshot.GenerationKind.OPENING, session.revision());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        AiClient unavailable = (s, p) -> {
            calls.incrementAndGet();
            throw new AiClient.AiException("network down", true, AiClient.AiException.Code.NETWORK, null, 0L);
        };
        try {
            runner.start(claim, unavailable);
            assertTrue(enteredWait.await(5, TimeUnit.SECONDS));
            assertEquals(InterviewSnapshot.GenerationState.RETRY_WAIT,
                    service.get("alice", session.sessionId()).generation().state());
            var finished = service.finish("alice", session.sessionId(), "finish-request", null);
            runner.cancel(claim.generationId());
            assertEquals("FINISHED", finished.status());
            assertTrue(service.get("alice", session.sessionId()).turns().isEmpty());
            assertEquals(1, calls.get());
        } finally {
            runner.cancel(claim.generationId());
            runner.close();
        }
    }

    @Test
    void racingAnswerSaveAndFinishNeverReopensSession() throws Exception {
        var service = new InterviewService(new InterviewAgent());
        var session = service.start("alice", "mat", "resume", "backend", 13,
                (s, p) -> question("介绍项目？"));
        var submission = new InterviewService.Submission(session.currentQuestion().questionId(), "race-sub", "竞态回答");
        var gate = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var save = executor.submit(() -> { gate.await(); try { service.saveAnswer("alice", session.sessionId(), submission); } catch (InterviewException ignored) {} return null; });
            var finish = executor.submit(() -> { gate.await(); service.finish("alice", session.sessionId(), "finish-request", null); return null; });
            gate.countDown(); save.get(5, TimeUnit.SECONDS); finish.get(5, TimeUnit.SECONDS);
            var finalState = service.get("alice", session.sessionId());
            assertEquals("FINISHED", finalState.status());
            assertTrue(finalState.turns().isEmpty() || finalState.turns().get(0).answer() == null
                    || "竞态回答".equals(finalState.turns().get(0).answer()));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void finishDoesNotWaitForBlockedOpeningAndRejectsLateQuestion() throws Exception {
        var service = new InterviewService(new InterviewAgent());
        var coordinator = new InterviewGenerationCoordinator(service);
        var executor = Executors.newSingleThreadExecutor();
        var runner = new InterviewGenerationRunner(service, coordinator, executor);
        var session = service.startAsync("alice", "mat", "resume", "backend", "open-request");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AiClient blocked = (s, p) -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            return question("迟到的问题？");
        };
        var claim = coordinator.claim("alice", session.sessionId(), "open-request",
                InterviewSnapshot.GenerationKind.OPENING, session.revision());
        try {
            runner.start(claim, blocked);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var finished = assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),
                    () -> service.finish("alice", session.sessionId(), "finish-request", null));
            runner.cancel(claim.generationId());
            assertEquals("FINISHED", finished.status());
            assertTrue(finished.turns().isEmpty());
            release.countDown();
            assertTrue(service.get("alice", session.sessionId()).turns().isEmpty());
        } finally {
            release.countDown();
            runner.close();
        }
    }

    @Test
    void anotherOwnerCannotFinishSession() {
        var service = new InterviewService(new InterviewAgent());
        var session = service.start("alice", "mat", "resume", "backend", 13,
                (s, p) -> question("介绍项目？"));
        assertThrows(InterviewException.class,
                () -> service.finish("mallory", session.sessionId(), "finish-request", null));
    }

    private static final class FailingStore implements InterviewStore {
        private final InterviewStore delegate;
        private final AtomicBoolean failNext = new AtomicBoolean();
        private FailingStore(InterviewStore delegate) { this.delegate = delegate; }
        @Override public void save(InterviewSnapshot snapshot) {
            if (failNext.getAndSet(false)) throw new InterviewException(500, "test storage failure");
            delegate.save(snapshot);
        }
        @Override public Optional<InterviewSnapshot> find(String owner, String id) { return delegate.find(owner, id); }
        @Override public Optional<InterviewSnapshot> findByStartRequestId(String owner, String requestId) {
            return delegate.findByStartRequestId(owner, requestId);
        }
        @Override public int deleteExpired(java.time.Instant now) { return delegate.deleteExpired(now); }
    }
}
