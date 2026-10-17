package com.shijie.resumehelper.interview;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InterviewVoiceSessionTest {
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-18T00:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
    }

    private InterviewVoiceProperties properties() {
        var p = new InterviewVoiceProperties(); p.setEnabled(true); return p;
    }
    private StreamingSpeechClient client() {
        return new VolcengineSpeechClient(new SpeechProperties(), new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test void ticketIsHashedSingleUseAndExpiresAfterSixtySeconds() throws Exception {
        var clock = new MutableClock(); var registry = new InterviewVoiceSessionRegistry(properties(), clock);
        var ticket = registry.issue("owner-a", "interview-a", client());
        assertNotNull(registry.consume(ticket.voiceSessionId(), ticket.ticket()));
        assertEquals(401, assertThrows(InterviewException.class,
                () -> registry.consume(ticket.voiceSessionId(), ticket.ticket())).status());
        var second = registry.issue("owner-a", "interview-a", client());
        clock.advanceSeconds(61);
        assertEquals(401, assertThrows(InterviewException.class,
                () -> registry.consume(second.voiceSessionId(), second.ticket())).status());
        registry.close("owner-a", "interview-a", ticket.voiceSessionId());
        registry.sweep(); assertEquals(0, registry.size());
    }

    @Test void ticketConsumptionIsAtomicAndCloseChecksOwnerAndSession() throws Exception {
        var registry = new InterviewVoiceSessionRegistry(properties(), new MutableClock());
        var ticket = registry.issue("owner-a", "interview-a", client());
        var ready = new CountDownLatch(1); var go = new CountDownLatch(1); var successes = new AtomicInteger();
        Runnable consume = () -> { try { ready.countDown(); go.await(); registry.consume(ticket.voiceSessionId(), ticket.ticket()); successes.incrementAndGet(); } catch (Exception ignored) { } };
        Thread a = new Thread(consume), b = new Thread(consume); a.start(); b.start(); ready.await(); go.countDown(); a.join(); b.join();
        assertEquals(1, successes.get());
        assertThrows(InterviewException.class, () -> registry.close("owner-b", "interview-a", ticket.voiceSessionId()));
        assertTrue(registry.close("owner-a", "interview-a", ticket.voiceSessionId()));
        assertFalse(registry.close("owner-a", "interview-a", ticket.voiceSessionId()));
        assertEquals(0, registry.size());
    }

    @Test void capsSessionsGloballyAndPerOwner() {
        var props = properties(); props.setMaxSessions(3); props.setMaxSessionsPerOwner(2);
        var registry = new InterviewVoiceSessionRegistry(props, new MutableClock());
        registry.issue("owner-a", "i1", client()); registry.issue("owner-a", "i2", client());
        assertEquals(429, assertThrows(InterviewException.class, () -> registry.issue("owner-a", "i3", client())).status());
        registry.issue("owner-b", "i3", client());
        assertEquals(429, assertThrows(InterviewException.class, () -> registry.issue("owner-c", "i4", client())).status());
    }
}
