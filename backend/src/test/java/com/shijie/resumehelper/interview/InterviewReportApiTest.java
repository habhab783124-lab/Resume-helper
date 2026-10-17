package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.common.ai.AiClientResolver;
import com.shijie.resumehelper.common.util.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.shijie.resumehelper.interview.InterviewTestFixtures.*;

@SpringBootTest(properties = {
        "app.material.storage-dir=./target/test-report-materials",
        "app.resume.storage-dir=./target/test-report-resumes"
})
@AutoConfigureMockMvc
class InterviewReportApiTest {
    private static final String QUESTION = "请介绍你负责的缓存优化项目？";
    private static final String ANSWER = "我实现了 Redis 缓存读写。";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired InterviewService interviews;
    @MockBean AiClientResolver resolver;
    private AiClient ai;
    private String owner;

    @BeforeEach void setUp() {
        owner = "report-" + UUID.randomUUID();
        ai = mock(AiClient.class);
        when(ai.withOutputBudget(anyInt())).thenReturn(ai);
        when(ai.chat(anyString(), anyString())).thenAnswer(call -> ((String) call.getArgument(1)).contains("selectedAlgorithm")
                ? algorithmFromPrompt(call.getArgument(1)) : question(QUESTION));
        when(ai.chat(anyString(), anyString(), eq(60))).thenAnswer(call -> {
            String system = call.getArgument(0);
            String prompt = call.getArgument(1);
            if (system.contains("当前模式：OPENING")) return question(QUESTION);
            if (system.contains("报告编辑")) return "{\"issues\":[{\"questionId\":\"" +
                    interviewsQuestionId(prompt) + "\",\"kind\":\"EVIDENCE\",\"evidenceQuote\":\"Redis\",\"issue\":\"未说明测试周期\",\"suggestedAction\":\"补充真实测试周期\"}]}";
            if (prompt.contains("selectedAlgorithm")) return algorithmFromPrompt(prompt);
            if (prompt.contains("\"inputs\"")) return assessmentsFromPrompt(prompt, 80);
            return question(QUESTION);
        });
        when(ai.name()).thenReturn("test-model");
        when(ai.available()).thenReturn(true);
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn(ai);
    }

    @Test void focusedPracticeEndpointRejectsTargetsUntilCurrentEvidenceSummaryIsComplete() throws Exception {
        var session = interviews.start(owner, "m1", "简历经历", "工程师", 3, (system, prompt) -> question(QUESTION));
        mvc.perform(post("/api/interviews/" + session.sessionId() + "/practice-plans")
                        .header(UserContext.HEADER, owner).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"issueIds\":[\"invented-question\"],\"requestId\":\"focused-before-review\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("需要先完成本轮报告重点归纳，再准备薄弱项练习"));
    }

    @Test void reviewIsQueuedAfterScoringAndItsRetryNeverRescoresAnswers() throws Exception {
        var session = completed(1);
        AtomicInteger reviewCalls = new AtomicInteger();
        when(ai.chat(anyString(), anyString(), eq(60))).thenAnswer(call -> {
            String system = call.getArgument(0), prompt = call.getArgument(1);
            if (system.contains("报告编辑")) {
                if (reviewCalls.incrementAndGet() == 1) return "invalid review JSON";
                return "{\"issues\":[{\"questionId\":\"" + session.turns().get(0).questionId()
                        + "\",\"kind\":\"EVIDENCE\",\"evidenceQuote\":\"Redis\",\"issue\":\"未说明测试周期\",\"suggestedAction\":\"补充真实测试周期\"}]}";
            }
            if (prompt.contains("\"inputs\"")) return assessmentsFromPrompt(prompt, 80);
            return question(QUESTION);
        });
        JsonNode report = asyncReport(session.sessionId());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        do {
            Thread.sleep(25);
            report = asyncReport(session.sessionId());
        } while (!"UNAVAILABLE".equals(report.path("reviewStatus").asText()) && System.nanoTime() < deadline);
        assertEquals("SUCCEEDED", report.path("scoringState").asText());
        assertEquals("UNAVAILABLE", report.path("reviewStatus").asText());
        assertEquals(1, reviewCalls.get());

        mvc.perform(post("/api/interviews/" + session.sessionId() + "/report/review/retry")
                        .header(UserContext.HEADER, owner).contentType("application/json")
                        .content("{\"requestId\":\"review-retry-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewStatus").value("PENDING"));
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        do {
            Thread.sleep(25);
            report = asyncReport(session.sessionId());
        } while (!"COMPLETE".equals(report.path("reviewStatus").asText()) && System.nanoTime() < deadline);
        assertEquals("COMPLETE", report.path("reviewStatus").asText());
        assertEquals(2, reviewCalls.get());
        assertEquals(session.turns().get(0).questionId(), report.at("/priorityIssues/0/questionId").asText());
        assertEquals("Redis", report.at("/priorityIssues/0/evidenceQuote").asText());
        verify(ai, times(1)).chat(anyString(), argThat(prompt -> prompt.contains("\"inputs\"")), eq(60));
        assertEquals("SUCCEEDED", report.path("scoringState").asText());
    }

    private static String interviewsQuestionId(String prompt) {
        var matcher = java.util.regex.Pattern.compile("questionId=([^\\n]+)").matcher(prompt);
        if (matcher.find()) return matcher.group(1);
        return "missing-question";
    }

    @Test void activeAndOtherUsersAreRejectedBeforeResolvingCredentials() throws Exception {
        var active = start(2);
        JsonNode before = sessionData(active.sessionId());
        report(active.sessionId(), owner).andExpect(status().isConflict());
        retry(active.sessionId(), owner).andExpect(status().isConflict());
        report(active.sessionId(), "other").andExpect(status().isNotFound());
        retry(active.sessionId(), "other").andExpect(status().isNotFound());
        verifyNoInteractions(resolver);
        assertEquals(before, sessionData(active.sessionId()));
    }

    @Test void reportUsesServerArithmeticAndKeepsCanonicalAnswersAndWeights() throws Exception {
        var session = completed(13);
        JsonNode before = sessionData(session.sessionId());
        report(session.sessionId(), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallScore").value(80.0))
                .andExpect(jsonPath("$.data.reportStatus").value("COMPLETE"))
                .andExpect(jsonPath("$.data.plannedCount").value(13))
                .andExpect(jsonPath("$.data.scoredCount").value(13))
                .andExpect(jsonPath("$.data.turnFeedbacks[0].weightPercent").value(6.67))
                .andExpect(jsonPath("$.data.turnFeedbacks[0].weightUnits").value(15))
                .andExpect(jsonPath("$.data.turnFeedbacks[0].question").value(QUESTION))
                .andExpect(jsonPath("$.data.turnFeedbacks[0].answer").value(ANSWER))
                .andExpect(jsonPath("$.data.turnFeedbacks[0].evidence[0]").value(ANSWER));
        // 评分会落盘 assessments 与报告缓存：快照版本前移，但会话逻辑内容不变（revision 只记逻辑提交）。
        assertEquals(dropStateVersion(before), dropStateVersion(sessionData(session.sessionId())));
        assertTrue(sessionData(session.sessionId()).at("/stateVersion").asLong() > before.at("/stateVersion").asLong());
    }

    @Test void finishedReportIsCachedWithoutResolvingChangedModelSettings() throws Exception {
        var session = completed(1);
        JsonNode first = reportData(session.sessionId());
        when(resolver.resolve(any(HttpServletRequest.class))).thenThrow(new IllegalArgumentException("changed configuration"));
        assertEquals(first, reportData(session.sessionId()));
        retry(session.sessionId(), owner).andExpect(status().isOk());
        verify(resolver, times(1)).resolve(any(HttpServletRequest.class));
        verify(ai, times(1)).chat(anyString(), anyString(), eq(60));
    }

    @Test void zeroAnswersNeedNoCredentialsAndHaveNoNumericScore() throws Exception {
        var session = start(2);
        interviews.finish(owner, session.sessionId());
        report(session.sessionId(), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("EMPTY"))
                .andExpect(jsonPath("$.data.overallScore").value(nullValue()))
                .andExpect(jsonPath("$.data.turnFeedbacks[0].status").value("UNANSWERED"));
        verifyNoInteractions(resolver);
    }

    @Test void asyncReportReturnsSavedAnswersBeforeScoringAndRefreshJoinsTheSameOperation() throws Exception {
        var session = completed(1);
        clearInvocations(ai);
        CountDownLatch scoringEntered = new CountDownLatch(1), releaseScoring = new CountDownLatch(1);
        when(ai.chat(anyString(), anyString(), eq(60))).thenAnswer(call -> {
            scoringEntered.countDown();
            assertTrue(releaseScoring.await(3, TimeUnit.SECONDS));
            return assessmentsFromPrompt(call.getArgument(1), 80);
        });
        try {
            long started = System.nanoTime();
            JsonNode first = asyncReport(session.sessionId());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000,
                    "async GET must return before the controlled model is released");
            assertEquals(ANSWER, first.at("/turnFeedbacks/0/answer").asText());
            assertTrue(Set.of("QUEUED", "RUNNING").contains(first.path("scoringState").asText()));
            assertTrue(scoringEntered.await(2, TimeUnit.SECONDS));

            JsonNode refreshed = asyncReport(session.sessionId());
            assertEquals(first.path("scoringOperationId").asText(), refreshed.path("scoringOperationId").asText());
            assertEquals(ANSWER, refreshed.at("/turnFeedbacks/0/answer").asText());
            verify(ai, times(1)).chat(anyString(), anyString(), eq(60));
            releaseScoring.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            JsonNode completed;
            do {
                Thread.sleep(25);
                completed = asyncReport(session.sessionId());
            } while (!"SUCCEEDED".equals(completed.path("scoringState").asText()) && System.nanoTime() < deadline);
            assertEquals("SUCCEEDED", completed.path("scoringState").asText());
            assertEquals("COMPLETE", completed.path("reportStatus").asText());
        } finally { releaseScoring.countDown(); }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void failedScoringIsCachedAndExplicitRetryRestoresScoring(boolean throwsError) throws Exception {
        var session = completed(13);
        if (throwsError) when(ai.chat(anyString(), anyString(), eq(60))).thenThrow(new AiClient.AiException("test timeout"));
        else when(ai.chat(anyString(), anyString(), eq(60))).thenReturn("invalid JSON");
        report(session.sessionId(), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.overallScore").value(nullValue()))
                .andExpect(jsonPath("$.data.strengths").isEmpty());
        report(session.sessionId(), owner).andExpect(status().isOk());
        verify(ai, times(3)).chat(anyString(), anyString(), eq(60));
        when(ai.chat(anyString(), anyString(), eq(60))).thenAnswer(call -> assessmentsFromPrompt(call.getArgument(1), 90));
        retry(session.sessionId(), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallScore").value(90.0));
    }

    @Test void unavailableCredentialsNeverCreateRuleScoresAndCanBeRetried() throws Exception {
        var session = completed(1);
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn(null);
        assertEquals("UNAVAILABLE", reportData(session.sessionId()).path("reportStatus").asText());
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn(ai);
        retry(session.sessionId(), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("COMPLETE"));
    }

    @Test void partialRetryOnlySendsMissingQuestionAndPreservesSuccessfulScore() throws Exception {
        var session = completed(2);
        String firstId = session.turns().get(0).questionId();
        String secondId = session.turns().get(1).questionId();
        when(ai.chat(anyString(), anyString(), eq(60))).thenReturn(json(Map.of("assessments",
                List.of(assessment(firstId, ANSWER, 80)))));
        report(session.sessionId(), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.data.overallScore").value(nullValue()))
                .andExpect(jsonPath("$.data.turnFeedbacks[0].score").value(80))
                .andExpect(jsonPath("$.data.turnFeedbacks[1].score").value(nullValue()));
        when(ai.chat(anyString(), anyString(), eq(60))).thenAnswer(call -> {
            var inputs = json.readTree((String) call.getArgument(1)).path("inputs");
            assertEquals(1, inputs.size());
            assertEquals(secondId, inputs.get(0).path("turn").path("questionId").asText());
            return assessmentsFromPrompt(call.getArgument(1), 90);
        });
        retry(session.sessionId(), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallScore").value(nullValue()))
                .andExpect(jsonPath("$.data.turnFeedbacks[0].score").value(80))
                .andExpect(jsonPath("$.data.turnFeedbacks[1].score").value(90));
    }

    @Test void pendingAnswerIsIncludedAfterFinishAndFailedNextQuestionDoesNotChangeContract() throws Exception {
        var session = start(2);
        when(ai.chat(anyString(), anyString(), eq(60))).thenThrow(new AiClient.AiException("failed next question"));
        assertThrows(InterviewException.class, () -> interviews.answer(owner, session.sessionId(),
                session.currentQuestion().questionId(), ANSWER, ai));
        report(session.sessionId(), owner).andExpect(status().isConflict());
        interviews.finish(owner, session.sessionId());
        report(session.sessionId(), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answeredCount").value(1))
                .andExpect(jsonPath("$.data.turnFeedbacks[0].answer").value(ANSWER))
                .andExpect(jsonPath("$.data.turnFeedbacks[0].weightUnits").value(15));
    }

    @Test void concurrentReportRequestsAreSingleFlightAndDoNotBlockSessionReads() throws Exception {
        var session = completed(1);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        AiClient slow = (system, prompt) -> {
            calls.incrementAndGet(); started.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            return assessmentsFromPrompt(prompt, 80);
        };
        var executor = Executors.newFixedThreadPool(3);
        try {
            var first = executor.submit(() -> interviews.report(owner, session.sessionId(), slow));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> interviews.report(owner, session.sessionId(), slow));
            var read = executor.submit(() -> interviews.get(owner, session.sessionId()));
            assertEquals("FINISHED", read.get(2, TimeUnit.SECONDS).status());
            release.countDown();
            assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void invalidRequestConfigurationKeepsFinishedSessionForLaterRetry() throws Exception {
        var session = completed(13);
        JsonNode before = sessionData(session.sessionId());
        when(resolver.resolve(any(HttpServletRequest.class))).thenThrow(new IllegalArgumentException("模型地址不正确"));
        report(session.sessionId(), owner).andExpect(status().isBadRequest());
        assertEquals(before, sessionData(session.sessionId()));
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn(ai);
        report(session.sessionId(), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallScore").value(80.0));
    }

    private InterviewService.Session start(int count) {
        return interviews.start(owner, "test-material", "Java Redis 项目，负责缓存优化。", "Java 工程师", count, ai);
    }
    private InterviewService.Session completed(int count) {
        var session = start(count);
        for (int i = 0; i < count; i++)
            session = interviews.answer(owner, session.sessionId(), session.currentQuestion().questionId(), ANSWER, ai);
        var finished = interviews.finish(owner, session.sessionId());
        clearInvocations(ai);
        return finished;
    }
    private ResultActions report(String id, String user) throws Exception {
        return mvc.perform(get("/api/interviews/" + id + "/report").header(UserContext.HEADER, user));
    }
    private ResultActions retry(String id, String user) throws Exception {
        return mvc.perform(post("/api/interviews/" + id + "/report/retry").header(UserContext.HEADER, user));
    }
    private JsonNode reportData(String id) throws Exception {
        return json.readTree(report(id, owner).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private JsonNode asyncReport(String id) throws Exception {
        return json.readTree(mvc.perform(get("/api/interviews/" + id + "/report").param("async", "true")
                        .header(UserContext.HEADER, owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private JsonNode sessionData(String id) throws Exception {
        return json.readTree(mvc.perform(get("/api/interviews/" + id).header(UserContext.HEADER, owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    /** stateVersion 随每次落盘递增，不参与「逻辑内容未变」的比较。 */
    private static JsonNode dropStateVersion(JsonNode node) {
        var copy = ((com.fasterxml.jackson.databind.node.ObjectNode) node).deepCopy();
        copy.remove("stateVersion");
        return copy;
    }
}
