package com.shijie.resumehelper.interview;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/** Retry classification and total-budget arithmetic for a single generation operation. */
public final class InterviewRetryPolicy {
    public record RetryDecision(boolean retry, long delayMs, int attemptTimeoutSeconds) {}

    private final int maxRetries;
    private final int configuredAttemptTimeoutSeconds;
    private final List<Long> delaysMs;
    private final LongSupplier jitter;

    public InterviewRetryPolicy(int maxRetries, int configuredAttemptTimeoutSeconds, List<Long> delaysMs) {
        this(maxRetries, configuredAttemptTimeoutSeconds, delaysMs,
                () -> ThreadLocalRandom.current().nextLong(251));
    }

    /** Jitter source is injectable so policy tests never depend on randomness. */
    public InterviewRetryPolicy(int maxRetries, int configuredAttemptTimeoutSeconds,
                                List<Long> delaysMs, LongSupplier jitter) {
        if (maxRetries < 0 || maxRetries > 5) throw new IllegalArgumentException("自动重试次数须在 0 到 5 之间");
        if (configuredAttemptTimeoutSeconds < 5) throw new IllegalArgumentException("单次超时不能少于 5 秒");
        this.maxRetries = maxRetries;
        this.configuredAttemptTimeoutSeconds = configuredAttemptTimeoutSeconds;
        this.delaysMs = delaysMs == null || delaysMs.isEmpty() ? List.of(0L) : List.copyOf(delaysMs);
        if (this.delaysMs.stream().anyMatch(delay -> delay == null || delay < 0))
            throw new IllegalArgumentException("重试间隔不能为负数");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
    }

    public RetryDecision decide(InterviewFailure failure, int attempt, int retryCount,
                                Instant deadlineAt, Instant now) {
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        Objects.requireNonNull(now, "now");
        long remainingMs = Math.max(0, Duration.between(now, deadlineAt).toMillis());
        if (failure == null || attempt >= maxRetries + 1 || retryCount >= maxRetries
                || !mayRetry(failure, retryCount) || remainingMs < 5000)
            return new RetryDecision(false, 0, 0);

        long base = delaysMs.get(Math.min(retryCount, delaysMs.size() - 1));
        long retryAfter = failure.retryAfterMs() == null ? 0 : Math.max(0, failure.retryAfterMs());
        long floor = Math.max(base, retryAfter);
        if (floor > remainingMs - 5000) return new RetryDecision(false, 0, 0);
        long jitterLimit = Math.min(250, Math.max(0, floor / 10));
        long jitterMs = jitterLimit == 0 ? 0 : Math.floorMod(jitter.getAsLong(), jitterLimit + 1);
        if (jitterMs > remainingMs - 5000 - floor) return new RetryDecision(false, 0, 0);
        long delay = floor + jitterMs;
        long remainingAfterDelay = remainingMs - delay;
        if (remainingAfterDelay < 5000) return new RetryDecision(false, 0, 0);
        int timeout = (int) Math.min(configuredAttemptTimeoutSeconds, remainingAfterDelay / 1000);
        if (timeout < 5) return new RetryDecision(false, 0, 0);
        return new RetryDecision(true, delay, timeout);
    }

    private boolean mayRetry(InterviewFailure failure, int retryCount) {
        return switch (failure.code()) {
            case TIMEOUT, NETWORK, UPSTREAM_UNAVAILABLE, RATE_LIMITED -> failure.retryable();
            case INVALID_OUTPUT -> retryCount == 0;
            case AUTH, CONFIG, QUOTA, INPUT, STORAGE, INTERRUPTED, UNKNOWN -> false;
        };
    }
}
