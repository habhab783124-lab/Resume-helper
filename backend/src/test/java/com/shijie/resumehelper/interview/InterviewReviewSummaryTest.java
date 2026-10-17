package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;

class InterviewReviewSummaryTest {
    private static InterviewReviewSummary.PriorityIssue issue(String qid, String quote, String kind) {
        return new InterviewReviewSummary.PriorityIssue(qid, InterviewReviewSummary.Kind.valueOf(kind), quote, "缺少验证闭环", "补充真实指标和对照方法");
    }

    @Test void validatesQuestionIdsEvidenceIssueKindsAndNoRepeatedClaimsForOneQuestion() {
        var context = new InterviewReviewSummary.ValidationContext(Map.of("q1", "我们上线了 Redis 缓存，响应时间下降。"), Set.of("q1"));
        assertEquals(1, InterviewReviewSummary.validate(List.of(issue("q1", "响应时间下降", "EVIDENCE")), context).size());
        assertThrows(IllegalArgumentException.class, () -> InterviewReviewSummary.validate(List.of(issue("fake", "响应时间下降", "EVIDENCE")), context));
        assertThrows(IllegalArgumentException.class, () -> InterviewReviewSummary.validate(List.of(issue("q1", "响应时间大幅下降", "EVIDENCE")), context));
        assertThrows(IllegalArgumentException.class, () -> InterviewReviewSummary.validate(List.of(issue("q1", "响应时间下降", "UNKNOWN")), context));
        assertThrows(IllegalArgumentException.class, () -> InterviewReviewSummary.validate(List.of(issue("q1", "响应时间下降", "EVIDENCE"), issue("q1", "响应时间下降", "EVIDENCE")), context));
        var fourAnswers = Map.of("q1", "证据甲", "q2", "证据乙", "q3", "证据丙", "q4", "证据丁");
        var fourIssues = fourAnswers.entrySet().stream().map(entry -> issue(entry.getKey(), entry.getValue(), "EVIDENCE")).toList();
        assertThrows(IllegalArgumentException.class, () -> InterviewReviewSummary.validate(fourIssues,
                new InterviewReviewSummary.ValidationContext(fourAnswers, fourAnswers.keySet())));
    }

    @Test void modelSummaryIsBoundedToThreeScoredEvidenceBackedDistinctQuestions() {
        var answers = Map.of("q1", "我用 A/B 对照验证转化提升。", "q2", "我将缓存命中率从 70% 提高到 90%。");
        var prompt = new AtomicReference<String>();
        var client = (AiClient) (system, user) -> { prompt.set(user); return """
                {"issues":[
                  {"questionId":"q1","kind":"REASONING","evidenceQuote":"A/B 对照","issue":"没有说明分组依据","suggestedAction":"解释分组与样本口径"},
                  {"questionId":"q2","kind":"EVIDENCE","evidenceQuote":"70% 提高到 90%","issue":"没有说明测量周期","suggestedAction":"补充测量周期"}]}
                """; };
        var result = new InterviewAgent().summarizeReview(answers, Map.of("q1", "已校验逐题反馈：说明了 A/B 对照"), Set.of("q1", "q2"), client);
        assertEquals(2, result.size());
        assertEquals("q1", result.get(0).questionId());
        assertTrue(prompt.get().contains("评分反馈"));
        assertTrue(prompt.get().contains("已校验逐题反馈"));
    }

    @Test void malformedInventedUnscoredOrUnanchoredSummaryFailsClosed() {
        var answers = Map.of("q1", "我完成了测试。");
        for (String json : List.of(
                "{\"issues\":[{\"questionId\":\"q2\",\"kind\":\"EVIDENCE\",\"evidenceQuote\":\"测试\",\"issue\":\"缺点\",\"suggestedAction\":\"补充\"}]}",
                "{\"issues\":[{\"questionId\":\"q1\",\"kind\":\"EVIDENCE\",\"evidenceQuote\":\"很多测试\",\"issue\":\"缺点\",\"suggestedAction\":\"补充\"}]}",
                "{\"issues\":[{\"questionId\":\"q1\",\"kind\":\"MADE_UP\",\"evidenceQuote\":\"测试\",\"issue\":\"缺点\",\"suggestedAction\":\"补充\"}]}",
                "{\"issues\":[{\"questionId\":\"q1\",\"kind\":\"EVIDENCE\",\"evidenceQuote\":\"测试\",\"issue\":\"缺点\",\"suggestedAction\":\"补充\"},{\"questionId\":\"q1\",\"kind\":\"EVIDENCE\",\"evidenceQuote\":\"测试\",\"issue\":\"第二缺点\",\"suggestedAction\":\"补充\"}]}")) {
            AiClient client = (system, user) -> json;
            assertThrows(InterviewException.class, () -> new InterviewAgent().summarizeReview(answers, client));
        }
        AiClient referencesUnscored = (system, user) -> "{\"issues\":[{\"questionId\":\"q2\",\"kind\":\"EVIDENCE\",\"evidenceQuote\":\"测试\",\"issue\":\"缺点\",\"suggestedAction\":\"补充\"}]}";
        assertThrows(InterviewException.class, () -> new InterviewAgent().summarizeReview(
                Map.of("q1", "其他回答", "q2", "我完成了测试。"), Set.of("q1"), referencesUnscored));
    }

    @Test void reviewFailureRetriesOnlyTheIndependentReviewOperation() {
        var service = new InterviewService(new InterviewAgent(), null, Clock.systemUTC());
        var session = service.start("alice", "m", "简历经历", "工程师", 13,
                (system, user) -> InterviewTestFixtures.question("介绍一个项目"));
        service.finish("alice", session.sessionId(), "finish", null);
        var scoring = service.queueReport("alice", session.sessionId(), "report", false);
        assertEquals("SUCCEEDED", scoring.report().scoringState());
        var review = service.queueReview("alice", session.sessionId(), "review-1", false);
        assertTrue(review.enqueue());
        assertTrue(service.startReview("alice", session.sessionId(), review.operationId()));
        service.failReview("alice", session.sessionId(), review.operationId(), "sensitive upstream detail");
        assertEquals("UNAVAILABLE", service.reportSnapshot("alice", session.sessionId()).reviewStatus());
        assertEquals("SUCCEEDED", service.reportSnapshot("alice", session.sessionId()).scoringState());
        assertFalse(service.queueReview("alice", session.sessionId(), "same", false).enqueue());
        var retry = service.queueReview("alice", session.sessionId(), "review-2", true);
        assertTrue(retry.enqueue());
        assertNotEquals(review.operationId(), retry.operationId());
        assertTrue(service.startReview("alice", session.sessionId(), retry.operationId()));
        assertTrue(service.completeReview("alice", session.sessionId(), retry.operationId(), List.of()));
        assertEquals("COMPLETE", service.reportSnapshot("alice", session.sessionId()).reviewStatus());
    }

    @Test void aNewScoringVersionFencesAnOlderSummaryResult() throws Exception {
        var service = new InterviewService(new InterviewAgent(), null, Clock.systemUTC());
        AiClient questions = (system, user) -> InterviewTestFixtures.question("问题：" + user.hashCode());
        var session = service.start("alice", "m", "简历经历", "工程师", 2, questions);
        for (int i = 0; i < 2; i++) {
            String qid = session.currentQuestion().questionId();
            session = service.answer("alice", session.sessionId(), qid, "真实回答包含验证步骤", questions);
        }
        service.finish("alice", session.sessionId(), "finish-scoring", null);
        var scoring = service.queueReport("alice", session.sessionId(), "score-1", false);
        assertTrue(service.startReportScoring("alice", session.sessionId(), scoring.operationId()));
        var reportAi = (AiClient) (system, prompt) -> {
            try {
                var first = new com.fasterxml.jackson.databind.ObjectMapper().readTree(prompt).path("inputs").get(0);
                String qid = first.path("turn").path("questionId").asText();
                String answer = first.path("turn").path("answer").asText();
                return InterviewTestFixtures.json(Map.of("assessments", List.of(InterviewTestFixtures.assessment(qid, answer, 80))));
            } catch (Exception error) { throw new AssertionError(error); }
        };
        service.report("alice", session.sessionId(), () -> reportAi, true);
        service.completeReportScoring("alice", session.sessionId(), scoring.operationId());
        var review = service.queueReview("alice", session.sessionId(), "review-old", false);
        var context = service.reviewContext("alice", session.sessionId(), review.operationId());
        String qid = context.answers().keySet().iterator().next();
        var issue = new InterviewReviewSummary.PriorityIssue(qid, InterviewReviewSummary.Kind.EVIDENCE,
                context.answers().get(qid).substring(0, 5), "证据口径不足", "补充真实测量周期");
        assertTrue(service.completeReview("alice", session.sessionId(), review.operationId(), List.of(issue)));
        var scoringRetry = service.queueReport("alice", session.sessionId(), "score-2", true);
        assertTrue(scoringRetry.enqueue());
        assertFalse(service.completeReview("alice", session.sessionId(), review.operationId(), List.of(issue)));
        assertEquals("PENDING", service.reportSnapshot("alice", session.sessionId()).reviewStatus());
    }
}
