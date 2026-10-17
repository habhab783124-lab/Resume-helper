package com.shijie.resumehelper.interview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpeechRequestLimiterTest {
    private SpeechProperties properties(int max) {
        var p = new SpeechProperties(); p.setMaxConcurrentRequests(max); return p;
    }

    @Test void sameOwnerMayUseAsrAndTtsConcurrentlyButNotSameLaneTwice() {
        var limiter = new SpeechRequestLimiter(properties(2));
        var asr = limiter.tryAcquire("owner-a", SpeechRequestLimiter.Lane.ASR);
        var tts = limiter.tryAcquire("owner-a", SpeechRequestLimiter.Lane.TTS);
        var denied = assertThrows(InterviewException.class,
                () -> limiter.tryAcquire("owner-a", SpeechRequestLimiter.Lane.ASR));
        assertEquals(429, denied.status());
        assertEquals(1000L, denied.failure().retryAfterMs());
        asr.close(); asr.close();
        try (var replacement = limiter.tryAcquire("owner-a", SpeechRequestLimiter.Lane.ASR)) {
            assertNotNull(replacement);
        }
        tts.close();
    }

    @Test void globalLimitAppliesAcrossOwnersAndLeaseClosesOnce() {
        var limiter = new SpeechRequestLimiter(properties(2));
        var one = limiter.tryAcquire("owner-a", SpeechRequestLimiter.Lane.ASR);
        var two = limiter.tryAcquire("owner-b", SpeechRequestLimiter.Lane.TTS);
        assertEquals(2, limiter.activeCount());
        assertEquals(429, assertThrows(InterviewException.class,
                () -> limiter.tryAcquire("owner-c", SpeechRequestLimiter.Lane.ASR)).status());
        one.close(); one.close();
        assertEquals(1, limiter.activeCount());
        two.close();
        assertEquals(0, limiter.activeCount());
    }
}
