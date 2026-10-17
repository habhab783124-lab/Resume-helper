package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.shijie.resumehelper.interview.InterviewTestFixtures.question;
import static org.junit.jupiter.api.Assertions.*;

class InterviewRetryRunnerTest {
    @Test
    void firstSuccessUsesOneAttemptAndThirdSuccessUsesTwoRetries() {
        var setup = setup();
        try {
            var first = opening(setup, "first-success");
            var oneCall = new AtomicInteger();
            runOpening(setup, first, (s, p) -> { oneCall.incrementAndGet(); return question("首题？"); });
            assertEquals(1, oneCall.get());
            assertEquals(1, setup.service.get("alice", first.sessionId()).generation().attempt());
            assertEquals(0, setup.service.get("alice", first.sessionId()).generation().retryCount());

            var third = opening(setup, "third-success");
            var calls = new AtomicInteger();
            runOpening(setup, third, (s, p) -> {
                if (calls.incrementAndGet() < 3) throw networkFailure();
                return question("第三次首题？");
            });
            var generation = setup.service.get("alice", third.sessionId()).generation();
            assertEquals(3, calls.get());
            assertEquals(3, generation.attempt());
            assertEquals(2, generation.retryCount());
            assertEquals(InterviewSnapshot.GenerationState.SUCCEEDED, generation.state());
        } finally { setup.close(); }
    }

    @Test
    void sixQuickTransientFailuresExhaustTheSingleOperationLimit() {
        var setup = setup();
        try {
            var opening = opening(setup, "six-failures");
            var calls = new AtomicInteger();
            AiClient ai = (s, p) -> { calls.incrementAndGet(); throw networkFailure(); };
            runOpening(setup, opening, ai);
            var generation = setup.service.get("alice", opening.sessionId()).generation();
            assertEquals(6, calls.get());
            assertEquals(6, generation.attempt());
            assertEquals(5, generation.retryCount());
            assertEquals(InterviewSnapshot.GenerationState.FAILED, generation.state());
        } finally { setup.close(); }
    }

    @Test
    void malformedOutputRetriesOnceAndAuthFailureDoesNotRetry() {
        var setup = setup();
        try {
            var invalidOutput = opening(setup, "invalid-output");
            var invalidCalls = new AtomicInteger();
            runOpening(setup, invalidOutput, (s, p) -> invalidCalls.incrementAndGet() == 1
                    ? "{}" : question("修复格式后的首题？"));
            var generation = setup.service.get("alice", invalidOutput.sessionId()).generation();
            assertEquals(2, invalidCalls.get());
            assertEquals(2, generation.attempt());
            assertEquals(1, generation.retryCount());

            var auth = opening(setup, "auth-failure");
            var authCalls = new AtomicInteger();
            AiClient invalidKey = (s, p) -> {
                authCalls.incrementAndGet();
                throw new AiClient.AiException("bad key", false, null,
                        AiClient.AiException.Code.AUTH, null, 0);
            };
            runOpening(setup, auth, invalidKey);
            assertEquals(1, authCalls.get());
            assertEquals(1, setup.service.get("alice", auth.sessionId()).generation().attempt());
        } finally { setup.close(); }
    }

    @Test
    void totalBudgetStopsBeforeSixCallsAndTimeoutIsPassedToHttpClient() {
        var properties = new InterviewProperties();
        properties.setRetryDelaysMs(List.of(0L));
        properties.setGenerationDeadlineSeconds(10);
        properties.setAttemptTimeoutSeconds(60);
        var clock = new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
        var setup = setup(properties, clock);
        try {
            var opening = opening(setup, "budget-expires");
            var calls = new AtomicInteger();
            AiClient slow = (s, p) -> {
                calls.incrementAndGet();
                clock.advanceSeconds(6);
                throw networkFailure();
            };
            runOpening(setup, opening, slow);
            var generation = setup.service.get("alice", opening.sessionId()).generation();
            assertEquals(1, calls.get());
            assertEquals(1, generation.attempt());
            assertEquals(0, generation.retryCount());
            assertEquals(InterviewSnapshot.GenerationState.FAILED, generation.state());
        } finally { setup.close(); }

        var timeoutProperties = new InterviewProperties();
        timeoutProperties.setGenerationDeadlineSeconds(10);
        timeoutProperties.setAttemptTimeoutSeconds(60);
        var timeoutSetup = setup(timeoutProperties, Clock.fixed(
                Instant.parse("2026-10-17T00:00:00Z"), ZoneId.of("UTC")));
        try {
            var opening = opening(timeoutSetup, "timeout-clamp");
            var seenTimeout = new AtomicInteger();
            AiClient timeoutAware = new AiClient() {
                @Override public String chat(String system, String prompt) { return question("被限制时长的首题？"); }
                @Override public String chat(String system, String prompt, int timeoutSeconds) {
                    seenTimeout.set(timeoutSeconds);
                    return question("被限制时长的首题？");
                }
            };
            runOpening(timeoutSetup, opening, timeoutAware);
            assertEquals(10, seenTimeout.get());
        } finally { timeoutSetup.close(); }
    }

    @Test
    void cancellingDuringRetryWaitDoesNotStartAnotherModelRequest() throws Exception {
        var properties = new InterviewProperties();
        properties.setRetryDelaysMs(List.of(60_000L));
        var waiting = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        Setup setup = setup(properties, Clock.systemUTC(), delay -> {
            waiting.countDown();
            try { new CountDownLatch(1).await(); }
            finally { exited.countDown(); }
        });
        try {
            var opening = opening(setup, "cancel-retry-wait");
            var calls = new AtomicInteger();
            AiClient ai = (s, p) -> { calls.incrementAndGet(); throw networkFailure(); };
            var claim = setup.coordinator.claim("alice", opening.sessionId(), opening.generation().requestId(),
                    InterviewSnapshot.GenerationKind.OPENING, opening.revision());
            setup.runner.start(claim, ai);
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            setup.service.finish("alice", opening.sessionId());
            setup.runner.cancel(claim.generationId());
            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(InterviewSnapshot.GenerationState.CANCELLED,
                    setup.service.get("alice", opening.sessionId()).generation().state());
        } finally { setup.close(); }
    }

    @Test
    void specialtyRetryDoesNotRepeatSuccessfulDecisionAndSharesAttemptBudget() {
        var setup = setup();
        try {
            String caseOpening = question("首题？").replace("ALGORITHM", "CASE_STUDY");
            AiClient initialAi = (s, p) -> caseOpening;
            var session = setup.service.start("alice", "mat", "resume", "产品经理", 13, initialAi);
            AiClient mainAi = (s, p) -> question("主问题？");
            while (session.turns().size() < 12)
                session = setup.service.answer("alice", session.sessionId(), session.currentQuestion().questionId(),
                        "回答", mainAi);
            session = setup.service.saveAnswer("alice", session.sessionId(), new InterviewService.Submission(
                    session.currentQuestion().questionId(), "last-answer", "最后一个主问题的回答"));
            var claim = setup.coordinator.claim("alice", session.sessionId(), "specialty-operation",
                    InterviewSnapshot.GenerationKind.NEXT, session.revision());
            var decisionCalls = new AtomicInteger();
            var specialtyCalls = new AtomicInteger();
            AiClient specialtyAi = (system, prompt) -> {
                if (system.contains("当前模式：CASE_STUDY")) {
                    if (specialtyCalls.incrementAndGet() == 1) throw networkFailure();
                    return question("自包含专业案例？");
                }
                decisionCalls.incrementAndGet();
                return "{\"kind\":\"SPECIALTY\"}";
            };
            setup.runner.start(claim, specialtyAi);
            setup.runner.await(claim.generationId(), java.time.Duration.ofSeconds(5));
            var generation = setup.service.get("alice", session.sessionId()).generation();
            assertEquals(1, decisionCalls.get());
            assertEquals(2, specialtyCalls.get());
            assertEquals(3, generation.attempt());
            assertEquals(1, generation.retryCount());
            assertEquals(InterviewSnapshot.GenerationState.SUCCEEDED, generation.state());
        } finally { setup.close(); }
    }

    private static Setup setup() {
        var properties = new InterviewProperties();
        properties.setRetryDelaysMs(List.of(0L));
        return setup(properties, Clock.systemUTC());
    }

    private static Setup setup(InterviewProperties properties, Clock clock) {
        return setup(properties, clock, Thread::sleep);
    }

    private static Setup setup(InterviewProperties properties, Clock clock,
                               InterviewService.RetrySleeper sleeper) {
        var service = new InterviewService(new InterviewAgent(), null, clock, properties, sleeper);
        var coordinator = new InterviewGenerationCoordinator(service);
        var executor = Executors.newFixedThreadPool(2);
        return new Setup(service, coordinator, new InterviewGenerationRunner(service, coordinator, executor));
    }

    private static InterviewService.Session opening(Setup setup, String requestId) {
        return setup.service.startAsync("alice", "mat", "Java experience", "backend", requestId);
    }

    private static void runOpening(Setup setup, InterviewService.Session session, AiClient ai) {
        var claim = setup.coordinator.claim("alice", session.sessionId(), session.generation().requestId(),
                InterviewSnapshot.GenerationKind.OPENING, session.revision());
        setup.runner.start(claim, ai);
        setup.runner.await(claim.generationId(), java.time.Duration.ofSeconds(5));
    }

    private static AiClient.AiException networkFailure() {
        return new AiClient.AiException("network", true, null,
                AiClient.AiException.Code.NETWORK, null, 0);
    }

    private record Setup(InterviewService service, InterviewGenerationCoordinator coordinator,
                         InterviewGenerationRunner runner) implements AutoCloseable {
        @Override public void close() { runner.close(); }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advanceSeconds(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
