package com.shijie.resumehelper.interview;

import static org.junit.jupiter.api.Assertions.*;

import com.shijie.resumehelper.common.ai.AiClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InterviewPlannedOpeningTest {
    @Test
    void openingUsesFrozenSpecialtyWithoutCallingLegacyReclassifier() {
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    AiClient ai, int timeoutSeconds) {
                assertEquals(InterviewPlan.SpecialtyKind.CASE_STUDY, plan.specialty().kind());
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
            @Override public Opening prepareOpening(String resume, String role, AiClient ai, int timeoutSeconds) {
                throw new AssertionError("confirmed plans must not reclassify their specialty");
            }
        };
        var service = new InterviewService(agent, null,
                Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC));
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");
        var resolved = new InterviewSourceResolver.ResolvedSource(source, "cv.txt", "简历正文", "fingerprint");
        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 3, 4, 2, 1,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "运营", "完整 JD",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.CASE_STUDY, "增长分析", "运营职责"),
                java.util.List.of(new InterviewPlan.Coverage("growth", "增长分析")), source, "cv.txt", "fingerprint",
                java.util.List.of());
        var session = service.startAsync("alice", resolved, plan, "start-1");
        var operation = session.generation();
        var claim = new InterviewGenerationCoordinator.GenerationClaim("alice", session.sessionId(),
                operation.generationId(), operation.requestId(), operation.kind(), session.revision());

        var result = service.generate(claim, (system, prompt) -> "unused");

        assertEquals(InterviewService.Kind.MAIN, result.kind());
        assertEquals(InterviewSpecialty.Kind.CASE_STUDY, result.specialty().kind());
        assertEquals("增长分析", result.specialty().focus());
    }
}
