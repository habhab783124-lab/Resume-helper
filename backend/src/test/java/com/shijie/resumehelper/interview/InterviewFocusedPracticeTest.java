package com.shijie.resumehelper.interview;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.ai.AiClient;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InterviewFocusedPracticeTest {
    private record Fixture(InterviewService service, String sessionId, String questionId) {}
    private Fixture completedService() throws Exception {
        var service = new InterviewService(new InterviewAgent(), null, Clock.systemUTC());
        AiClient questions = (system, user) -> InterviewTestFixtures.question("请描述一个你验证结果的项目？");
        var session = service.start("alice", "m1", "Java 项目简历", "工程师", 1, questions);
        String questionId = session.currentQuestion().questionId();
        service.answer("alice", session.sessionId(), questionId, "我用对照组验证了接口响应时间下降。", questions);
        service.finish("alice", session.sessionId(), "finish", null);
        AiClient scoring = (system, user) -> {
            try {
                var root = new ObjectMapper().readTree(user);
                var input = root.path("inputs").get(0);
                String qid = input.path("turn").path("questionId").asText();
                String answer = input.path("turn").path("answer").asText();
                return InterviewTestFixtures.json(Map.of("assessments", List.of(InterviewTestFixtures.assessment(qid, answer, 70))));
            } catch (Exception error) { throw new AssertionError(error); }
        };
        var scoringTask = service.queueReport("alice", session.sessionId(), "score-1", false);
        assertTrue(service.startReportScoring("alice", session.sessionId(), scoringTask.operationId()));
        service.report("alice", session.sessionId(), () -> scoring, true);
        service.completeReportScoring("alice", session.sessionId(), scoringTask.operationId());
        var review = service.queueReview("alice", session.sessionId(), "review-1", false);
        assertTrue(service.startReview("alice", session.sessionId(), review.operationId()));
        var context = service.reviewContext("alice", session.sessionId(), review.operationId());
        String answer = context.answers().get(questionId);
        assertTrue(service.completeReview("alice", session.sessionId(), review.operationId(), List.of(
                new InterviewReviewSummary.PriorityIssue(questionId, InterviewReviewSummary.Kind.EVIDENCE,
                        "对照组", "结果证据不足", "补充测量周期和量化结果"))));
        return new Fixture(service, session.sessionId(), questionId);
    }

    @Test void activeRoundsCannotProducePracticeTargetsBeforeACompletedSummary() {
        var service = new InterviewService(new InterviewAgent(), null, Clock.systemUTC());
        var active = service.start("alice", "m1", "简历经历", "工程师", 3,
                (system, prompt) -> InterviewTestFixtures.question("请介绍一个项目？"));
        assertThrows(InterviewException.class, () -> service.focusedPracticeContext("alice", active.sessionId(), List.of("q1")));
        assertThrows(InterviewException.class, () -> service.focusedPracticeContext("alice", "deleted-source", List.of("q1")));
    }

    @Test void acceptsOnlyTargetsFromTheCurrentCompletedEvidenceBackedReview() throws Exception {
        var fixture = completedService();
        var context = fixture.service().focusedPracticeContext("alice", fixture.sessionId(), List.of(fixture.questionId()));
        assertEquals(fixture.sessionId(), context.sourceSessionId());
        assertEquals(new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1"), context.source());
        assertEquals(1, context.targets().size());
        assertEquals(fixture.questionId(), context.targets().get(0).issueId());
        assertEquals("结果证据不足", context.targets().get(0).title());
        fixture.service().validatePracticeSource("alice", fixture.sessionId(), context.targets());
        assertThrows(IllegalArgumentException.class, () -> fixture.service().focusedPracticeContext("alice", fixture.sessionId(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> fixture.service().focusedPracticeContext("alice", fixture.sessionId(),
                List.of("q1", "q2", "q3", "q4")));
        assertThrows(IllegalArgumentException.class, () -> fixture.service().focusedPracticeContext("alice", fixture.sessionId(),
                List.of(fixture.questionId(), fixture.questionId())));
        assertThrows(IllegalArgumentException.class, () -> fixture.service().focusedPracticeContext("alice", fixture.sessionId(), List.of("unknown")));
        assertThrows(InterviewException.class, () -> fixture.service().focusedPracticeContext("bob", fixture.sessionId(), List.of(fixture.questionId())));
        assertThrows(InterviewException.class, () -> fixture.service().validatePracticeSource("alice", fixture.sessionId(),
                List.of(new InterviewPlan.PracticeTarget(fixture.questionId(), "伪造的标题", "伪造动作", List.of(fixture.questionId())))));
    }
}
