package com.shijie.resumehelper.interview;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InterviewModeFlowTest {
    private static InterviewPlan planned(InterviewPlan.Mode mode, int budget, int perMain) {
        return new InterviewPlan("experience-v1", mode, mode == InterviewPlan.Mode.QUICK ? 3 : 12,
                mode == InterviewPlan.Mode.QUICK ? 3 : 13, budget, perMain,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "", ""), List.of(),
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1"), "简历", "fp", List.of());
    }

    @Test
    void newPlansEnforceSessionAndPerRootFollowUpBudgets() {
        var quick = planned(InterviewPlan.Mode.QUICK, 2, 1);
        assertTrue(quick.allowsFollowUp(0, 0));
        assertFalse(quick.allowsFollowUp(0, 1));
        assertTrue(quick.allowsFollowUp(1, 0));
        assertFalse(quick.allowsFollowUp(2, 0));

        var full = planned(InterviewPlan.Mode.FULL, 6, 2);
        assertTrue(full.allowsFollowUp(5, 1));
        assertFalse(full.allowsFollowUp(5, 2));
        assertFalse(full.allowsFollowUp(6, 0));
    }

    @Test
    void legacyPlanKeepsPerRootLimitWithoutSessionBudget() {
        var legacy = InterviewPlan.legacyV3(
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1"), "工程师");
        assertTrue(legacy.allowsFollowUp(36, 1));
        assertFalse(legacy.allowsFollowUp(36, 2));
    }

    @Test
    void quickThreeAndFivePlansEndAtTheirFrozenCountsWhenNoSpecialtyWasConfirmed() {
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
            @Override public Proposal preparePlannedNext(String resume, String role, List<InterviewService.Turn> history,
                    int total, int nextMainIndex, boolean allowFollowUp, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                assertEquals(plan.mainCount(), total);
                return new Proposal(InterviewTestFixtures.contract("CORE", "INTERMEDIATE"), false,
                        plan.coverage().get(0).id());
            }
        };
        com.shijie.resumehelper.common.ai.AiClient ai = (system, prompt) -> "unused";
        for (int count : List.of(3, 5)) {
            var service = new InterviewService(agent, null,
                    Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC));
            var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");
            var resolved = new InterviewSourceResolver.ResolvedSource(source, "cv.txt", "resume", "fp");
            var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, count, count, 2, 1,
                    InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                    new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "", ""),
                    List.of(new InterviewPlan.Coverage("impact", "项目效果")),
                    source, "cv.txt", "fp", List.of());
            var session = service.startAsync("alice", resolved, plan, "start-quick-" + count);
            var opening = claim(service, session, "open-quick-" + count);
            session = service.commitGenerationSuccess("alice", session.sessionId(), opening.generationId(),
                    opening.expectedRevision(), service.generate(opening, ai));
            for (int i = 1; i <= count; i++) {
                var question = session.currentQuestion();
                session = service.saveAnswer("alice", session.sessionId(),
                        new InterviewService.Submission(question.questionId(), "answer-" + i, "回答" + i));
                var next = claim(service, session, "next-" + i + "-" + count);
                session = service.commitGenerationSuccess("alice", session.sessionId(), next.generationId(),
                        next.expectedRevision(), service.generate(next, ai));
            }
            assertEquals("FINISHED", session.status());
            assertEquals("COMPLETED", session.finishReason());
            assertEquals(count, session.turns().size());
            assertNull(session.currentQuestion());
            assertEquals(count, session.questionCount());
        }
    }

    @Test
    void skippingRootPersistsClosureAndPreventsItsFollowUp() {
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
            @Override public Proposal preparePlannedNext(String resume, String role, List<InterviewService.Turn> history,
                    int total, int nextMainIndex, boolean allowFollowUp, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                assertFalse(allowFollowUp);
                return new Proposal(InterviewTestFixtures.contract("CORE", "INTERMEDIATE"), false,
                        plan.coverage().get(0).id());
            }
        };
        var service = new InterviewService(agent, null,
                Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC));
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");
        var resolved = new InterviewSourceResolver.ResolvedSource(source, "cv.txt", "resume", "fp");
        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 3, 3, 2, 1,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "", ""),
                List.of(new InterviewPlan.Coverage("impact", "项目效果")),
                source, "cv.txt", "fp", List.of());
        com.shijie.resumehelper.common.ai.AiClient ai = (system, prompt) -> "unused";
        var session = service.startAsync("alice", resolved, plan, "start-skip");
        var opening = claim(service, session, "open-skip");
        session = service.commitGenerationSuccess("alice", session.sessionId(), opening.generationId(),
                opening.expectedRevision(), service.generate(opening, ai));
        String rootId = session.currentQuestion().questionId();

        var controller = new InterviewController(service, agent, null, null,
                new InterviewGenerationCoordinator(service), null, null, null);
        jakarta.servlet.http.HttpServletRequest request = userRequest("alice");
        session = controller.skip(session.sessionId(), new InterviewController.SkipRequest(
                rootId, "skip-root-1", session.revision()), request).data();
        assertEquals(List.of(rootId), List.copyOf(session.closedRootIds()));
        assertEquals(1, session.progress().skippedRoots());
        assertEquals(1, session.progress().resolvedRoots());
        assertEquals(0, session.progress().answeredRoots());
        assertNull(session.currentQuestion());

        var next = claim(service, session, "advance-skip");
        session = service.commitGenerationSuccess("alice", session.sessionId(), next.generationId(),
                next.expectedRevision(), service.generate(next, ai));
        assertEquals(2, session.questionMetadata().get(session.currentQuestion().questionId()).mainQuestionIndex());
        assertEquals(1, session.progress().skippedRoots());
    }

    @Test
    void clarificationControllerQueuesOnceAndSkipInvalidatesLateModelResult() throws Exception {
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
        };
        var service = new InterviewService(agent, null, Clock.systemUTC());
        var plan = planned(InterviewPlan.Mode.QUICK, 2, 1);
        var source = new InterviewSourceResolver.ResolvedSource(plan.source(), "简历", "正文", "fp");
        var queued = service.startAsync("alice", source, plan, "clarify-controller-start");
        var opening = claim(service, queued, "clarify-controller-open");
        var session = service.commitGenerationSuccess("alice", queued.sessionId(), opening.generationId(),
                opening.expectedRevision(), service.generate(opening, (system, prompt) -> "unused"));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var modelCalls = new java.util.concurrent.atomic.AtomicInteger();
        com.shijie.resumehelper.common.ai.AiClient ai = (system, prompt) -> {
            modelCalls.incrementAndGet(); entered.countDown();
            try { if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("release timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            return "只解释任务要求";
        };
        var clients = new com.shijie.resumehelper.common.ai.AiClientResolver(
                new com.shijie.resumehelper.common.ai.AiProperties(), new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override public com.shijie.resumehelper.common.ai.AiClient resolve(jakarta.servlet.http.HttpServletRequest request) { return ai; }
        };
        var controller = new InterviewController(service, agent, clients, null,
                new InterviewGenerationCoordinator(service), null, null, null);
        var request = userRequest("alice");
        String questionId = session.currentQuestion().questionId();
        var body = new InterviewController.ClarificationRequest(questionId, "clarify-controller-1", session.revision());
        var accepted = controller.clarify(session.sessionId(), body, request).data();
        assertEquals(InterviewSnapshot.ClarificationStatus.RUNNING, accepted.clarifications().get(questionId).status());
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        controller.clarify(session.sessionId(), body, request);
        assertEquals(1, modelCalls.get(), "same request ID must not enqueue a second model call");
        var skipped = controller.skip(session.sessionId(), new InterviewController.SkipRequest(
                questionId, "skip-after-clarify-controller", accepted.revision()), request).data();
        release.countDown();
        assertEquals(InterviewSnapshot.ClarificationStatus.CANCELLED, skipped.clarifications().get(questionId).status());
        assertTrue(waitUntil(() -> service.get("alice", session.sessionId()).clarifications().get(questionId).status()
                != InterviewSnapshot.ClarificationStatus.RUNNING));
        var late = service.get("alice", session.sessionId()).clarifications().get(questionId);
        assertEquals(InterviewSnapshot.ClarificationStatus.CANCELLED, late.status());
        assertNull(late.text());
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        return condition.getAsBoolean();
    }

    @Test
    void fullPlanRunsTwelveMainQuestionsThenOneConfirmedSpecialty() {
        var nextCalls = new java.util.concurrent.atomic.AtomicInteger();
        var specialtyCalls = new java.util.concurrent.atomic.AtomicInteger();
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
            @Override public Proposal preparePlannedNext(String resume, String role, List<InterviewService.Turn> history,
                    int total, int nextMainIndex, boolean allowFollowUp, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                nextCalls.incrementAndGet();
                assertEquals(12, total);
                return new Proposal(InterviewTestFixtures.contract("CORE", "INTERMEDIATE"), false,
                        plan.coverage().get(0).id());
            }
            @Override public InterviewQuestion prepareCase(InterviewSpecialty specialty, String role, InterviewPlan.Level level,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                specialtyCalls.incrementAndGet();
                assertEquals(InterviewSpecialty.Kind.CASE_STUDY, specialty.kind());
                return InterviewTestFixtures.contract("CORE", "ADVANCED");
            }
        };
        var service = new InterviewService(agent, null,
                Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC));
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");
        var resolved = new InterviewSourceResolver.ResolvedSource(source, "cv.txt", "resume", "fp");
        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.FULL, 12, 13, 6, 2,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.EXPERIENCED, "产品经理", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.CASE_STUDY, "商业分析", "岗位要求"),
                List.of(new InterviewPlan.Coverage("impact", "项目效果")), source, "cv.txt", "fp", List.of());
        com.shijie.resumehelper.common.ai.AiClient ai = (system, prompt) -> "unused";
        var session = service.startAsync("alice", resolved, plan, "start-full");
        var opening = claim(service, session, "open-full");
        session = service.commitGenerationSuccess("alice", session.sessionId(), opening.generationId(),
                opening.expectedRevision(), service.generate(opening, ai));
        for (int i = 1; i <= 12; i++) {
            var question = session.currentQuestion();
            session = service.saveAnswer("alice", session.sessionId(),
                    new InterviewService.Submission(question.questionId(), "full-answer-" + i, "回答" + i));
            var next = claim(service, session, "full-next-" + i);
            session = service.commitGenerationSuccess("alice", session.sessionId(), next.generationId(),
                    next.expectedRevision(), service.generate(next, ai));
        }
        assertEquals(13, session.turns().size());
        assertEquals(13, session.questionCount());
        assertEquals(InterviewService.Kind.CASE_STUDY,
                session.questionMetadata().get(session.currentQuestion().questionId()).kind());
        assertEquals(12, session.progress().answeredRoots());
        assertEquals(12, nextCalls.get());
        assertEquals(1, specialtyCalls.get());

        session = service.saveAnswer("alice", session.sessionId(), new InterviewService.Submission(
                session.currentQuestion().questionId(), "full-specialty-answer", "案例回答"));
        assertEquals("FINISHED", session.status());
        assertEquals("COMPLETED", session.finishReason());
    }

    @Test
    void serviceRejectsCoverageIdEvenWhenAgentAdapterReturnsIt() {
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
            @Override public Proposal preparePlannedNext(String resume, String role, List<InterviewService.Turn> history,
                    int total, int nextMainIndex, boolean allowFollowUp, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return new Proposal(InterviewTestFixtures.contract("CORE", "INTERMEDIATE"), false, "invented-id");
            }
        };
        var service = new InterviewService(agent, null,
                Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC));
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");
        var resolved = new InterviewSourceResolver.ResolvedSource(source, "cv.txt", "resume", "fp");
        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 3, 3, 2, 1,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "", ""),
                List.of(new InterviewPlan.Coverage("impact", "项目效果")), source, "cv.txt", "fp", List.of());
        com.shijie.resumehelper.common.ai.AiClient ai = (system, prompt) -> "unused";
        var session = service.startAsync("alice", resolved, plan, "start-invalid-coverage");
        var opening = claim(service, session, "open-invalid-coverage");
        session = service.commitGenerationSuccess("alice", session.sessionId(), opening.generationId(),
                opening.expectedRevision(), service.generate(opening, ai));
        var current = session.currentQuestion();
        session = service.saveAnswer("alice", session.sessionId(),
                new InterviewService.Submission(current.questionId(), "answer-invalid-coverage", "回答"));
        var next = claim(service, session, "next-invalid-coverage");

        InterviewException error = assertThrows(InterviewException.class, () -> service.generate(next, ai));
        assertEquals(InterviewFailure.Code.INVALID_OUTPUT, error.failure().code());
    }

    @Test
    void quickAndFullPlansRespectMaximumAnswerBudgets() {
        assertMaximumAnswers(InterviewPlan.Mode.QUICK, 5, 2, 1, true, 8);
        assertMaximumAnswers(InterviewPlan.Mode.FULL, 12, 6, 2, true, 19);
    }

    @Test
    void quickTurnApiSavesAnswerAndWaitsForNextStepChoice() {
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
        };
        var service = new InterviewService(agent, null,
                Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC));
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");
        var resolved = new InterviewSourceResolver.ResolvedSource(source, "cv.txt", "resume", "fp");
        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 3, 3, 2, 1,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "", ""),
                List.of(new InterviewPlan.Coverage("impact", "项目效果")), source, "cv.txt", "fp", List.of());
        com.shijie.resumehelper.common.ai.AiClient ai = (system, prompt) -> "unused";
        var session = service.startAsync("alice", resolved, plan, "start-quick-api");
        var opening = claim(service, session, "open-quick-api");
        session = service.commitGenerationSuccess("alice", session.sessionId(), opening.generationId(),
                opening.expectedRevision(), service.generate(opening, ai));
        var controller = new InterviewController(service, agent, null, null,
                new InterviewGenerationCoordinator(service), null, null, null);
        jakarta.servlet.http.HttpServletRequest request = userRequest("alice");

        var saved = controller.answer(session.sessionId(), new InterviewController.AnswerRequest(
                session.currentQuestion().questionId(), "回答"), request).data();

        assertNull(saved.currentQuestion());
        assertEquals(1, saved.turns().size());
        assertEquals("回答", saved.turns().get(0).answer());
        assertEquals(InterviewSnapshot.GenerationState.SUCCEEDED, saved.generation().state());
        assertEquals(session.revision() + 1, saved.revision());
    }

    @Test
    void fullTurnApiAutomaticallyQueuesTheNextQuestion() {
        com.shijie.resumehelper.common.ai.AiClient ai = (system, prompt) -> "unused";
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient client, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
            @Override public Proposal preparePlannedNext(String resume, String role, List<InterviewService.Turn> history,
                    int total, int nextMainIndex, boolean allowFollowUp, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient client, int timeoutSeconds) {
                return new Proposal(InterviewTestFixtures.contract("CORE", "INTERMEDIATE"), false,
                        plan.coverage().get(0).id());
            }
        };
        var service = new InterviewService(agent, null,
                Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC));
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");
        var resolved = new InterviewSourceResolver.ResolvedSource(source, "cv.txt", "resume", "fp");
        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.FULL, 12, 13, 6, 2,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.EXPERIENCED, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.CASE_STUDY, "案例", "岗位要求"),
                List.of(new InterviewPlan.Coverage("impact", "项目效果")), source, "简历", "fp", List.of());
        var session = service.startAsync("alice", resolved, plan, "start-full-api");
        var opening = claim(service, session, "open-full-api");
        session = service.commitGenerationSuccess("alice", session.sessionId(), opening.generationId(),
                opening.expectedRevision(), service.generate(opening, ai));
        var coordinator = new InterviewGenerationCoordinator(service);
        var runner = new InterviewGenerationRunner(service, coordinator,
                java.util.concurrent.Executors.newSingleThreadExecutor());
        var clients = new com.shijie.resumehelper.common.ai.AiClientResolver(
                new com.shijie.resumehelper.common.ai.AiProperties(), new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override public com.shijie.resumehelper.common.ai.AiClient resolve(jakarta.servlet.http.HttpServletRequest request) {
                return ai;
            }
        };
        try {
            var controller = new InterviewController(service, agent, clients, null, coordinator, runner, null, null);
            var next = controller.answer(session.sessionId(), new InterviewController.AnswerRequest(
                    session.currentQuestion().questionId(), "回答"), userRequest("alice")).data();
            assertEquals(2, next.turns().size());
            assertEquals("ACTIVE", next.status());
            assertNotNull(next.currentQuestion());
            assertEquals(2, next.questionMetadata().get(next.currentQuestion().questionId()).mainQuestionIndex());
        } finally {
            runner.close();
        }
    }

    private static void assertMaximumAnswers(InterviewPlan.Mode mode, int mainCount, int followUpBudget,
            int perMain, boolean specialty, int expectedAnswers) {
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
            @Override public Proposal preparePlannedNext(String resume, String role, List<InterviewService.Turn> history,
                    int total, int nextMainIndex, boolean allowFollowUp, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return allowFollowUp
                        ? new Proposal(InterviewTestFixtures.contract("CORE", "INTERMEDIATE"), true, null)
                        : new Proposal(InterviewTestFixtures.contract("CORE", "INTERMEDIATE"), false,
                                plan.coverage().get(0).id());
            }
            @Override public InterviewQuestion prepareCase(InterviewSpecialty specialty, String role, InterviewPlan.Level level,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "ADVANCED");
            }
        };
        var service = new InterviewService(agent, null,
                Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC));
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");
        var resolved = new InterviewSourceResolver.ResolvedSource(source, "cv.txt", "resume", "fp");
        int plannedCount = mainCount + (specialty ? 1 : 0);
        var plan = new InterviewPlan("experience-v1", mode, mainCount, plannedCount, followUpBudget, perMain,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.EXPERIENCED, "工程师", "",
                specialty ? new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.CASE_STUDY, "案例", "岗位要求")
                        : new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "", ""),
                List.of(new InterviewPlan.Coverage("impact", "项目效果")), source, "简历", "fp", List.of());
        com.shijie.resumehelper.common.ai.AiClient ai = (system, prompt) -> "unused";
        var session = service.startAsync("alice", resolved, plan, "start-budget-" + mode);
        var opening = claim(service, session, "open-budget-" + mode);
        session = service.commitGenerationSuccess("alice", session.sessionId(), opening.generationId(),
                opening.expectedRevision(), service.generate(opening, ai));
        int answered = 0, generationNumber = 0;
        while ("ACTIVE".equals(session.status())) {
            var question = session.currentQuestion();
            session = service.saveAnswer("alice", session.sessionId(), new InterviewService.Submission(
                    question.questionId(), "budget-answer-" + mode + "-" + answered, "回答"));
            answered++;
            if (!"ACTIVE".equals(session.status())) break;
            var next = claim(service, session, "budget-next-" + mode + "-" + generationNumber++);
            session = service.commitGenerationSuccess("alice", session.sessionId(), next.generationId(),
                    next.expectedRevision(), service.generate(next, ai));
        }
        assertEquals(expectedAnswers, answered);
        assertEquals(plannedCount + followUpBudget, session.turns().size());
        assertEquals(followUpBudget, session.progress().followUpsUsed());
        assertEquals("COMPLETED", session.finishReason());
    }

    @Test
    void closingRootCancelsInFlightDecisionAndRejectsLateFollowUp() {
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
        };
        var service = new InterviewService(agent, null,
                Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC));
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");
        var resolved = new InterviewSourceResolver.ResolvedSource(source, "cv.txt", "resume", "fp");
        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 3, 3, 2, 1,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "", ""),
                List.of(new InterviewPlan.Coverage("impact", "项目效果")),
                source, "cv.txt", "fp", List.of());
        var session = service.startAsync("alice", resolved, plan, "start-close-flight");
        var opening = claim(service, session, "open-close-flight");
        com.shijie.resumehelper.common.ai.AiClient ai = (system, prompt) -> "unused";
        session = service.commitGenerationSuccess("alice", session.sessionId(), opening.generationId(),
                opening.expectedRevision(), service.generate(opening, ai));
        String rootId = session.currentQuestion().questionId();
        session = service.saveAnswer("alice", session.sessionId(),
                new InterviewService.Submission(rootId, "answer-before-close", "已回答"));
        String generationId = service.claimGeneration("alice", session.sessionId(), "in-flight-next",
                InterviewSnapshot.GenerationKind.NEXT, session.revision());
        long generationRevision = session.revision();
        String sessionId = session.sessionId();

        var closed = service.closeRoot("alice", sessionId, rootId, "close-in-flight", generationRevision);
        assertEquals(InterviewSnapshot.GenerationState.CANCELLED, closed.generation().state());
        var lateFollowUp = new InterviewService.GenerationResult(InterviewTestFixtures.contract("CORE", "INTERMEDIATE"),
                InterviewService.Kind.FOLLOW_UP, rootId, 1, null, null, null);
        assertThrows(InterviewException.class, () -> service.commitGenerationSuccess("alice", sessionId,
                generationId, generationRevision, lateFollowUp));
        assertEquals(1, closed.progress().answeredRoots());
        assertEquals(0, closed.progress().skippedRoots());
    }

    private static InterviewGenerationCoordinator.GenerationClaim claim(InterviewService service,
            InterviewService.Session session, String requestId) {
        String id = service.claimGeneration("alice", session.sessionId(), requestId,
                session.turns().isEmpty() ? InterviewSnapshot.GenerationKind.OPENING
                        : InterviewSnapshot.GenerationKind.NEXT, session.revision());
        return new InterviewGenerationCoordinator.GenerationClaim("alice", session.sessionId(), id, requestId,
                session.turns().isEmpty() ? InterviewSnapshot.GenerationKind.OPENING
                        : InterviewSnapshot.GenerationKind.NEXT, session.revision());
    }

    private static jakarta.servlet.http.HttpServletRequest userRequest(String userId) {
        return (jakarta.servlet.http.HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(
                InterviewModeFlowTest.class.getClassLoader(),
                new Class<?>[]{jakarta.servlet.http.HttpServletRequest.class}, (proxy, method, args) ->
                        method.getName().equals("getHeader") && "X-User-Id".equals(args[0]) ? userId : null);
    }
}
