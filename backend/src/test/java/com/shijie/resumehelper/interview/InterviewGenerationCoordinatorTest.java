package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;

import static com.shijie.resumehelper.interview.InterviewTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * T05：生成操作的认领与回写（计划 4.2 第 4–7 条）。
 * 覆盖相同请求加入同操作、不同请求撞活跃操作只加入、旧 revision 被拒、
 * 两次成功回写只追加一题、失败回写、迟到结果失效，以及 OPENING/NEXT 前置条件。
 * 不调用真实模型：用 {@link InterviewService#saveAnswer} 准备已答会话，用 mock {@link InterviewService.GenerationResult} 回写。
 */
class InterviewGenerationCoordinatorTest {

    private static final String RESUME = "张同学：Java Spring Boot 校园项目，负责 Redis 缓存优化。";

    private final InterviewService service = new InterviewService(new InterviewAgent());
    private final InterviewGenerationCoordinator coordinator = new InterviewGenerationCoordinator(service);

    private AiClient openingAi() {
        return (s, p) -> question("请介绍你的项目？");
    }

    /** 准备一场已保存首题回答、等待下一题的会话。 */
    private InterviewService.Session answeredSession() {
        var session = service.start("alice", "mat-1", RESUME, "Java 后端", 13, openingAi());
        service.saveAnswer("alice", session.sessionId(),
                new InterviewService.Submission(session.currentQuestion().questionId(), "sub-1", "通过监控验证缓存"));
        return service.get("alice", session.sessionId());
    }

    private InterviewService.GenerationResult nextMainResult() {
        return new InterviewService.GenerationResult(contract("CORE", "INTERMEDIATE"),
                InterviewService.Kind.MAIN, null, 2, null, null, null);
    }

    @Test
    void sameRequestJoinsSameOperation() {
        var session = answeredSession();
        long revision = session.revision();
        var claim1 = coordinator.claim("alice", session.sessionId(), "req-1",
                InterviewSnapshot.GenerationKind.NEXT, revision);
        var claim2 = coordinator.claim("alice", session.sessionId(), "req-1",
                InterviewSnapshot.GenerationKind.NEXT, revision);
        assertEquals(claim1.generationId(), claim2.generationId(), "同 requestId 应加入同一操作");
    }

    @Test
    void differentRequestJoinsActiveOperationInsteadOfSecondTask() {
        var session = answeredSession();
        var claim1 = coordinator.claim("alice", session.sessionId(), "req-1",
                InterviewSnapshot.GenerationKind.NEXT, session.revision());
        var claim2 = coordinator.claim("alice", session.sessionId(), "req-2",
                InterviewSnapshot.GenerationKind.NEXT, session.revision());
        assertEquals(claim1.generationId(), claim2.generationId(), "活跃操作存在时不排第二个任务");
    }

    @Test
    void staleRevisionIsRejectedWhenNoActiveOperation() {
        var session = answeredSession();
        assertThrows(InterviewException.class, () -> coordinator.claim("alice", session.sessionId(), "req-1",
                InterviewSnapshot.GenerationKind.NEXT, session.revision() + 999));
    }

    @Test
    void commitSuccessAppendsExactlyOneQuestionAndSucceeds() {
        var session = answeredSession();
        long revision = session.revision();
        int turnsBefore = session.turns().size();
        var claim = coordinator.claim("alice", session.sessionId(), "req-1",
                InterviewSnapshot.GenerationKind.NEXT, revision);

        var committed = coordinator.commitSuccess(claim, nextMainResult());
        assertEquals(turnsBefore + 1, committed.turns().size(), "成功回写只追加一题");
        assertNotNull(committed.currentQuestion(), "新题成为当前题");
        assertEquals(revision + 1, committed.revision(), "题目变更新增一次 revision");

        // 第二次提交同一操作：已 SUCCEEDED，拒绝重复追加
        assertThrows(InterviewException.class, () -> coordinator.commitSuccess(claim, nextMainResult()));
        assertEquals(turnsBefore + 1, service.get("alice", session.sessionId()).turns().size(), "重复提交不得追加第二题");
    }

    @Test
    void lateCommitFailsAfterRevisionChanged() {
        var session = answeredSession();
        var claim = coordinator.claim("alice", session.sessionId(), "req-1",
                InterviewSnapshot.GenerationKind.NEXT, session.revision());
        // 第一次成功回写推进了 revision
        coordinator.commitSuccess(claim, nextMainResult());
        // 用旧 revision 的 claim 再提交：迟到结果应被拒
        assertThrows(InterviewException.class, () -> coordinator.commitSuccess(claim, nextMainResult()));
    }

    @Test
    void commitFailureMarksOperationFailedWithoutChangingQuestions() {
        var session = answeredSession();
        long revision = session.revision();
        int turnsBefore = session.turns().size();
        var claim = coordinator.claim("alice", session.sessionId(), "req-1",
                InterviewSnapshot.GenerationKind.NEXT, revision);

        var failed = coordinator.commitFailure(claim,
                new InterviewFailure(InterviewFailure.Code.NETWORK, true, "无法连接模型服务", null));
        assertEquals(turnsBefore, failed.turns().size(), "失败回写不追加题目");
        assertEquals(revision, failed.revision(), "失败回写不增加 revision");
        // 失败后该操作不再接受提交
        assertThrows(InterviewException.class, () -> coordinator.commitSuccess(claim, nextMainResult()));
    }

    @Test
    void openingClaimRejectedWhenFirstQuestionAlreadyExists() {
        var session = answeredSession();
        assertThrows(InterviewException.class, () -> coordinator.claim("alice", session.sessionId(), "req-1",
                InterviewSnapshot.GenerationKind.OPENING, session.revision()));
    }

    @Test
    void nextClaimRejectedWhenCurrentQuestionUnanswered() {
        var session = service.start("alice", "mat-1", RESUME, "Java 后端", 13, openingAi());
        // 首题尚未回答：currentQuestionId 仍指向首题，不能认领 NEXT
        assertThrows(InterviewException.class, () -> coordinator.claim("alice", session.sessionId(), "req-1",
                InterviewSnapshot.GenerationKind.NEXT, session.revision()));
    }

    @Test
    void generationDoesNotInvokeModel() {
        // claim/commit 都不应调用模型；saveAnswer 也不调用。这里 service 没有注入 ai，
        // 任何误调用都会以 NPE/空指针暴露在测试中。
        var session = answeredSession();
        var claim = coordinator.claim("alice", session.sessionId(), "req-1",
                InterviewSnapshot.GenerationKind.NEXT, session.revision());
        coordinator.commitSuccess(claim, nextMainResult());
        assertNotNull(service.get("alice", session.sessionId()).currentQuestion());
    }
}
