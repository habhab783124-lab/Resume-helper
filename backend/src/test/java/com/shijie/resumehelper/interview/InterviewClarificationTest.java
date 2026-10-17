package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class InterviewClarificationTest {
    @Test void clarificationExplainsOnlyTheQuestionWithoutSendingAnswersOrScoringCriteria() {
        var prompts = new AtomicReference<String[]>(null);
        AiClient ai = (system, user) -> {
            prompts.set(new String[] { system, user });
            return "这里的缓存一致性是指多份数据副本保持可接受的一致状态。";
        };
        var result = new InterviewAgent().clarifyQuestion("请解释你如何处理缓存一致性。", ai);
        assertEquals("这里的缓存一致性是指多份数据副本保持可接受的一致状态。", result);
        assertTrue(prompts.get()[1].contains("请解释你如何处理缓存一致性。"));
        assertFalse(prompts.get()[1].contains("参考答案"));
        assertFalse(prompts.get()[1].contains("评分标准"));
        assertTrue(prompts.get()[0].contains("不得作答"));
    }

    @Test void clarificationRejectsOversizedModelOutputSoOperationCannotRemainRunning() {
        AiClient ai = (system, user) -> "解".repeat(2001);
        var error = assertThrows(InterviewException.class,
                () -> new InterviewAgent().clarifyQuestion("请解释术语。", ai));
        assertEquals(502, error.status());
    }

    @Test void clarificationOperationIsIdempotentAndLateResultCannotApplyAfterSkip() {
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan, AiClient ai, int timeout) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
        };
        var service = new InterviewService(agent, null, Clock.systemUTC());
        var sourcePlan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 2, 2, 1, 1,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "", ""),
                List.of(new InterviewPlan.Coverage("impact", "项目成果")),
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m-clarify"), "简历", "fp", List.of());
        var resolved = new InterviewSourceResolver.ResolvedSource(sourcePlan.source(), "简历", "正文", "fp");
        var queued = service.startAsync("alice", resolved, sourcePlan, "clarify-start");
        var claim = new InterviewGenerationCoordinator.GenerationClaim("alice", queued.sessionId(),
                queued.generation().generationId(), queued.generation().requestId(), queued.generation().kind(), queued.revision());
        var session = service.commitGenerationSuccess("alice", queued.sessionId(), claim.generationId(), claim.expectedRevision(),
                service.generate(claim, (system, prompt) -> "unused"));
        String questionId = session.currentQuestion().questionId();
        var operation = service.beginClarification("alice", session.sessionId(), questionId, "clarify-1", session.revision());
        var duplicate = service.beginClarification("alice", session.sessionId(), questionId, "clarify-1", session.revision());
        assertEquals(operation.clarifications().get(questionId).requestId(), duplicate.clarifications().get(questionId).requestId());
        service.closeRoot("alice", session.sessionId(), questionId, "skip-after-clarify", operation.revision());
        assertFalse(service.completeClarification("alice", session.sessionId(), questionId, "clarify-1", "辅助解释"));
        assertNull(service.get("alice", session.sessionId()).clarifications().get(questionId).text());
    }

    @Test void completedClarificationLeavesQuestionAndContractUnchangedAndFinishRejectsLateResult() {
        var fixture = openInterview();
        var service = fixture.service(); var session = fixture.session();
        String questionId = session.currentQuestion().questionId();
        var operation = service.beginClarification("alice", session.sessionId(), questionId, "clarify-complete", session.revision());
        assertTrue(service.completeClarification("alice", session.sessionId(), questionId, "clarify-complete", "解释术语和任务，不含答案"));
        var clarified = service.get("alice", session.sessionId());
        assertEquals(session.currentQuestion(), clarified.currentQuestion());
        assertEquals(session.turns(), clarified.turns());
        assertEquals(InterviewSnapshot.ClarificationStatus.COMPLETE,
                clarified.clarifications().get(questionId).status());
        var repeated = assertThrows(InterviewException.class, () -> service.beginClarification("alice",
                session.sessionId(), questionId, "clarify-second-success", clarified.revision()));
        assertEquals(409, repeated.status());

        var second = openInterview();
        var running = second.service().beginClarification("alice", second.session().sessionId(),
                second.session().currentQuestion().questionId(), "clarify-finish", second.session().revision());
        second.service().finish("alice", second.session().sessionId(), "finish-during-clarify", null);
        assertFalse(second.service().completeClarification("alice", second.session().sessionId(),
                second.session().currentQuestion().questionId(), "clarify-finish", "迟到解释"));
        assertEquals(InterviewSnapshot.ClarificationStatus.CANCELLED,
                second.service().get("alice", second.session().sessionId()).clarifications()
                        .get(second.session().currentQuestion().questionId()).status());
    }

    @Test void failedClarificationCanBeRetriedLocallyWithANewRequestId() {
        var fixture = openInterview(); var session = fixture.session(); var service = fixture.service();
        String questionId = session.currentQuestion().questionId();
        var operation = service.beginClarification("alice", session.sessionId(), questionId, "clarify-fail", session.revision());
        assertTrue(service.failClarification("alice", session.sessionId(), questionId, "clarify-fail", "模型暂不可用"));
        var failed = service.get("alice", session.sessionId());
        assertEquals(InterviewSnapshot.ClarificationStatus.FAILED, failed.clarifications().get(questionId).status());
        assertEquals(session.turns(), failed.turns());
        var retry = service.beginClarification("alice", session.sessionId(), questionId, "clarify-retry-new-id", failed.revision());
        assertEquals(InterviewSnapshot.ClarificationStatus.RUNNING, retry.clarifications().get(questionId).status());
        assertNotEquals(operation.clarifications().get(questionId).requestId(), retry.clarifications().get(questionId).requestId());
    }

    private record Fixture(InterviewService service, InterviewService.Session session) {}
    private static Fixture openInterview() {
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan, AiClient ai, int timeout) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
        };
        var service = new InterviewService(agent, null, Clock.systemUTC());
        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 2, 2, 1, 1,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "", ""),
                List.of(new InterviewPlan.Coverage("impact", "项目成果")),
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m-clarify"), "简历", "fp", List.of());
        var source = new InterviewSourceResolver.ResolvedSource(plan.source(), "简历", "正文", "fp");
        var queued = service.startAsync("alice", source, plan, "clarify-start");
        var generation = queued.generation();
        var claim = new InterviewGenerationCoordinator.GenerationClaim("alice", queued.sessionId(), generation.generationId(),
                generation.requestId(), generation.kind(), queued.revision());
        var session = service.commitGenerationSuccess("alice", queued.sessionId(), claim.generationId(), claim.expectedRevision(),
                service.generate(claim, (system, prompt) -> "unused"));
        return new Fixture(service, session);
    }
}
