package com.shijie.resumehelper.interview;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

class InterviewStoreMaintenanceTest {
    @Test
    void cleanupCallsStoreWithCurrentTimeAndConfiguredBound() {
        Instant[] observedTime = new Instant[1];
        int[] observedLimit = new int[1];
        InterviewStore store = new InterviewStore() {
            @Override public void save(InterviewSnapshot snapshot) { throw new UnsupportedOperationException(); }
            @Override public java.util.Optional<InterviewSnapshot> find(String owner, String sessionId) { return java.util.Optional.empty(); }
            @Override public java.util.Optional<InterviewSnapshot> findByStartRequestId(String owner, String requestId) { return java.util.Optional.empty(); }
            @Override public int deleteExpired(Instant now) { throw new UnsupportedOperationException(); }
            @Override public int deleteExpired(Instant now, int limit) { observedTime[0] = now; observedLimit[0] = limit; return 0; }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-10-17T08:00:00Z"), ZoneOffset.UTC);

        new InterviewStoreMaintenance(store, clock, 7).cleanupExpired();

        org.junit.jupiter.api.Assertions.assertEquals(clock.instant(), observedTime[0]);
        org.junit.jupiter.api.Assertions.assertEquals(7, observedLimit[0]);
    }
}
