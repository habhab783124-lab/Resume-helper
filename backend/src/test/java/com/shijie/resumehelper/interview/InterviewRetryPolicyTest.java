package com.shijie.resumehelper.interview;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InterviewRetryPolicyTest {
    private final Instant now = Instant.parse("2026-10-17T00:00:00Z");
    private final InterviewFailure transientFailure = new InterviewFailure(
            InterviewFailure.Code.NETWORK, true, "network", null);

    @Test
    void allowsAtMostFiveRetriesForFastTransientFailures() {
        var policy = policy(5, 60, List.of(0L, 0L, 0L, 0L, 0L));
        for (int attempt = 1; attempt <= 5; attempt++)
            assertTrue(policy.decide(transientFailure, attempt, attempt - 1,
                    now.plusSeconds(120), now).retry());
        assertFalse(policy.decide(transientFailure, 6, 5, now.plusSeconds(120), now).retry());
    }

    @Test
    void usesConfiguredDelayAndHonorsRetryAfterWithoutCrossingDeadline() {
        var policy = policy(5, 60, List.of(1000L, 2000L, 4000L));
        var retryAfter = new InterviewFailure(InterviewFailure.Code.RATE_LIMITED, true,
                "limited", 3000L);
        var decision = policy.decide(retryAfter, 1, 0, now.plusSeconds(30), now);
        assertTrue(decision.retry());
        assertTrue(decision.delayMs() >= 3000L);
        assertEquals(27, decision.attemptTimeoutSeconds());

        var tooLate = policy.decide(transientFailure, 1, 0, now.plusSeconds(5), now);
        assertFalse(tooLate.retry());
    }

    @Test
    void invalidOutputGetsOnlyOneRetryAndAuthOrQuotaNeverRetry() {
        var policy = policy(5, 60, List.of(0L, 0L, 0L));
        var invalid = new InterviewFailure(InterviewFailure.Code.INVALID_OUTPUT, true, "invalid", null);
        assertTrue(policy.decide(invalid, 1, 0, now.plusSeconds(60), now).retry());
        assertFalse(policy.decide(invalid, 2, 1, now.plusSeconds(60), now).retry());
        for (var code : List.of(InterviewFailure.Code.AUTH, InterviewFailure.Code.CONFIG,
                InterviewFailure.Code.QUOTA, InterviewFailure.Code.INPUT, InterviewFailure.Code.STORAGE)) {
            var failure = new InterviewFailure(code, true, "no", null);
            assertFalse(policy.decide(failure, 1, 0, now.plusSeconds(60), now).retry(), code.name());
        }
    }

    @Test
    void shortensEachAttemptTimeoutToRemainingBudgetAndRejectsLessThanFiveSeconds() {
        var policy = policy(5, 60, List.of(0L));
        var almostOut = policy.decide(transientFailure, 1, 0, now.plusSeconds(12), now);
        assertTrue(almostOut.retry());
        assertEquals(12, almostOut.attemptTimeoutSeconds());
        assertFalse(policy.decide(transientFailure, 1, 0, now.plusSeconds(4), now).retry());
    }

    private static InterviewRetryPolicy policy(int retries, int timeout, List<Long> delays) {
        return new InterviewRetryPolicy(retries, timeout, delays, () -> 0L);
    }
}
