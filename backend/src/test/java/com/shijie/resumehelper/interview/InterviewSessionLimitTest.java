package com.shijie.resumehelper.interview;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InterviewSessionLimitTest {
    private static final String OWNER = "limit-owner";
    private static final InterviewPlan.ResumeSource SOURCE =
            new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "material-1");
    private static final InterviewPlan PLAN = InterviewPlan.legacyV3(SOURCE, "后端工程师");
    private static final InterviewSourceResolver.ResolvedSource RESOLVED =
            new InterviewSourceResolver.ResolvedSource(SOURCE, "简历", "resume text", PLAN.sourceFingerprint());

    @Test
    void rejectsTheTwentyFirstActiveSession() {
        assertLimit(new InterviewStore.SessionCounts(20, 20));
    }

    @Test
    void rejectsTheOneHundredAndFirstTotalSessionEvenWhenNoneAreActive() {
        assertLimit(new InterviewStore.SessionCounts(0, 100));
    }

    private static void assertLimit(InterviewStore.SessionCounts counts) {
        InterviewStore store = mock(InterviewStore.class);
        when(store.counts(org.mockito.ArgumentMatchers.eq(OWNER), org.mockito.ArgumentMatchers.any()))
                .thenReturn(counts);
        InterviewService service = new InterviewService(mock(InterviewAgent.class), store,
                Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC), new InterviewProperties());

        InterviewException error = assertThrows(InterviewException.class,
                () -> service.startAsync(OWNER, RESOLVED, PLAN, "request-1"));
        assertEquals(429, error.status(), error.getMessage());
    }
}
