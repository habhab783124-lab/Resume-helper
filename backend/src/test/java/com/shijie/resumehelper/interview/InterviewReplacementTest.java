package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class InterviewReplacementTest {
    @Test void replacementPromptUsesDenialReasonAndDoesNotContinueAssumingTheExperience() {
        var captured = new AtomicReference<String>();
        AiClient ai = (system, user) -> {
            captured.set(system + "\n" + user);
            return """
                    {"question":"你会如何评估一次由你负责的缓存优化？","competency":"缓存能力",
                    "importance":"CORE","difficulty":"INTERMEDIATE","weightReason":"岗位要求",
                    "criteria":[
                    {"dimension":"CORRECTNESS","expectation":"说明评估方法"},
                    {"dimension":"COVERAGE","expectation":"覆盖优化前后对比"},
                    {"dimension":"REASONING","expectation":"解释方法选择"},
                    {"dimension":"EVIDENCE","expectation":"给出真实依据"},
                    {"dimension":"CLARITY","expectation":"结构清晰"}]}
                    """;
        };
        var original = InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
        var replacement = new InterviewAgent().prepareReplacement("Java 简历", "Java 后端", original,
                "缓存项目不是我负责的，我没有参与", ai);
        assertNotEquals(original.text(), replacement.text());
        assertTrue(captured.get().contains("缓存项目不是我负责的，我没有参与"));
        assertTrue(captured.get().contains("不得假设候选人参与过被否认的经历"));
    }

    @Test void replacementSucceedsAtSameRootIndexAndConsumesOneReplacementAllowance() {
        var fixture = open(); var service = fixture.service(); var session = fixture.session();
        var original = session.currentQuestion(); String oldId = original.questionId();
        var pending = service.beginReplacement("alice", session.sessionId(), oldId, "replace-1", session.revision(),
                "这项经历不是我负责的，我没有参与该项目。");
        var duplicate = service.beginReplacement("alice", session.sessionId(), oldId, "replace-1", session.revision(),
                "这项经历不是我负责的，我没有参与该项目。");
        assertEquals(pending.stateVersion(), duplicate.stateVersion());
        var originalContract = InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
        var alternative = new InterviewQuestion("你会如何验证自己负责的缓存优化？", originalContract.competency(),
                originalContract.importance(), originalContract.difficulty(), originalContract.weightReason(), originalContract.criteria(),
                InterviewScoringPolicy.VERSION);
        assertTrue(service.completeReplacement("alice", session.sessionId(), oldId, "replace-1", alternative));
        var changed = service.get("alice", session.sessionId());
        assertNotEquals(oldId, changed.currentQuestion().questionId());
        assertEquals(1, changed.questionMetadata().get(changed.currentQuestion().questionId()).mainQuestionIndex());
        assertTrue(changed.withdrawnQuestionIds().contains(oldId));
        assertNotNull(changed.turns().stream().filter(t -> t.questionId().equals(oldId)).findFirst().orElseThrow());
        var second = assertThrows(InterviewException.class, () -> service.beginReplacement("alice", session.sessionId(),
                changed.currentQuestion().questionId(), "replace-2", changed.revision(), "我没有做过原题里的项目"));
        assertEquals(409, second.status());
    }

    @Test void failedReplacementKeepsSourceAndAllowsRetryButAnswerSkipAndFinishReject() {
        var fixture = open(); var service = fixture.service(); var session = fixture.session();
        String oldId = session.currentQuestion().questionId();
        var running = service.beginReplacement("alice", session.sessionId(), oldId, "replace-fail", session.revision(), "我没有参与这项经历");
        assertTrue(service.failReplacement("alice", session.sessionId(), oldId, "replace-fail", "模型暂不可用"));
        var failed = service.get("alice", session.sessionId());
        assertEquals(oldId, failed.currentQuestion().questionId());
        assertEquals(InterviewSnapshot.ReplacementStatus.FAILED, failed.replacements().get(oldId).status());
        assertNotNull(service.beginReplacement("alice", session.sessionId(), oldId, "replace-retry", failed.revision(), "我没有参与这项经历"));

        var another = open(); var answered = another.service().saveAnswer("alice", another.session().sessionId(),
                new InterviewService.Submission(another.session().currentQuestion().questionId(), "replace-answer", "我的回答"));
        assertEquals(409, assertThrows(InterviewException.class, () -> another.service().beginReplacement("alice",
                answered.sessionId(), another.session().currentQuestion().questionId(), "replace-answered", answered.revision(), "不是我的经历")).status());
        var ended = open(); ended.service().finish("alice", ended.session().sessionId(), "replace-finish", null);
        assertEquals(409, assertThrows(InterviewException.class, () -> ended.service().beginReplacement("alice",
                ended.session().sessionId(), ended.session().currentQuestion().questionId(), "replace-finished", ended.session().revision(), "不是我的经历")).status());
    }

    @Test void replacementReasonIsRequiredAndBounded() {
        var fixture = open(); var service = fixture.service(); var session = fixture.session();
        String qid = session.currentQuestion().questionId();
        assertThrows(IllegalArgumentException.class, () -> service.beginReplacement("alice", session.sessionId(), qid,
                "replace-empty", session.revision(), " "));
        assertThrows(IllegalArgumentException.class, () -> service.beginReplacement("alice", session.sessionId(), qid,
                "replace-long", session.revision(), "原因".repeat(151)));
    }

    @Test void skippedSourceCancelsReplacementAndRejectsItsLateModelResult() {
        var fixture = open(); var service = fixture.service(); var session = fixture.session();
        String qid = session.currentQuestion().questionId();
        var running = service.beginReplacement("alice", session.sessionId(), qid, "replace-late", session.revision(), "这段经历不是我做的");
        var skipped = service.closeRoot("alice", session.sessionId(), qid, "skip-race", running.revision());
        assertEquals(InterviewSnapshot.ReplacementStatus.CANCELLED, skipped.replacements().get(qid).status());
        assertFalse(service.completeReplacement("alice", session.sessionId(), qid, "replace-late",
                InterviewTestFixtures.contract("CORE", "INTERMEDIATE")));
        assertTrue(service.get("alice", session.sessionId()).closedRootIds().contains(qid));
        assertNull(service.get("alice", session.sessionId()).turns().stream()
                .filter(turn -> turn.questionId().equals(qid)).findFirst().orElseThrow().answer());
    }

    @Test void modelCannotChangeTheFrozenCompetencyOrDifficulty() {
        var fixture = open(); var service = fixture.service(); var session = fixture.session();
        String qid = session.currentQuestion().questionId();
        service.beginReplacement("alice", session.sessionId(), qid, "replace-contract", session.revision(), "不是我做的经历");
        var wrong = new InterviewQuestion("解释排序算法", "算法", InterviewQuestion.Importance.CORE, InterviewQuestion.Difficulty.ADVANCED, "岗位要求",
                InterviewTestFixtures.contract("CORE", "INTERMEDIATE").criteria(), InterviewScoringPolicy.VERSION);
        assertThrows(InterviewException.class, () -> service.completeReplacement("alice", session.sessionId(), qid,
                "replace-contract", wrong));
        assertEquals(qid, service.get("alice", session.sessionId()).currentQuestion().questionId());
        assertFalse(service.get("alice", session.sessionId()).withdrawnQuestionIds().contains(qid));
    }

    private record Fixture(InterviewService service, InterviewService.Session session) {}
    private static Fixture open() {
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
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m-replace"), "简历", "fp", List.of());
        var source = new InterviewSourceResolver.ResolvedSource(plan.source(), "简历", "正文", "fp");
        var queued = service.startAsync("alice", source, plan, "replace-start"); var generation = queued.generation();
        var claim = new InterviewGenerationCoordinator.GenerationClaim("alice", queued.sessionId(), generation.generationId(),
                generation.requestId(), generation.kind(), queued.revision());
        var session = service.commitGenerationSuccess("alice", queued.sessionId(), claim.generationId(), claim.expectedRevision(),
                service.generate(claim, (system, prompt) -> "unused"));
        return new Fixture(service, session);
    }
}
