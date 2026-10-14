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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.material.storage-dir=./target/test-report-materials",
        "app.resume.storage-dir=./target/test-report-resumes"
})
@AutoConfigureMockMvc
class InterviewReportApiTest {
    private static final String QUESTION = "请介绍你负责的缓存优化项目？";
    private static final String ANSWER = "我引入 Redis 缓存，将接口延迟从 200ms 降低到 50ms。";
    private static final String MODEL_REPORT = """
            {"overallScore":88,"overallSummary":"行动和结果清晰",
             "strengths":["贡献明确","结果量化"],"gaps":["缺少故障案例","缺少容量说明"],
             "suggestions":["说明降级方案","补充压测数据"],
             "turnFeedbacks":[{"index":1,"score":90,"highlight":"量化延迟改善","improvement":"补充缓存失效策略"}]}
            """;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired InterviewService interviews;
    @MockBean AiClientResolver resolver;
    private AiClient ai;
    private String owner;

    @BeforeEach void setUp() {
        owner = "report-" + UUID.randomUUID();
        ai = mock(AiClient.class);
        when(ai.chat(anyString(), anyString())).thenReturn(QUESTION);
        when(ai.chat(anyString(), anyString(), eq(60))).thenReturn(MODEL_REPORT);
        when(ai.name()).thenReturn("test-model");
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn(ai);
    }

    @Test void activeReportReturns409WithoutChangingSession() throws Exception {
        var session = start(2);
        JsonNode before = sessionData(session.sessionId());
        report(session.sessionId(), owner).andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("面试尚未结束，请先完成所有回答或手动结束面试"));
        assertEquals(before, sessionData(session.sessionId()));
    }

    @Test void anotherUserCannotReadFinishedReportOrChangeOwnersSession() throws Exception {
        var session = completed();
        JsonNode before = sessionData(session.sessionId());
        report(session.sessionId(), "other-" + owner).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("面试不存在或已过期，请重新开始"));
        assertEquals(before, sessionData(session.sessionId()));
        report(session.sessionId(), owner).andExpect(status().isOk());
    }

    @Test void finishedReportParsesModelFeedbackAndKeepsAnsweredTurns() throws Exception {
        var session = completed();
        JsonNode before = sessionData(session.sessionId());
        report(session.sessionId(), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.aiModel").value("test-model"))
                .andExpect(jsonPath("$.data.overallScore").value(88))
                .andExpect(jsonPath("$.data.overallSummary").value("行动和结果清晰"))
                .andExpect(jsonPath("$.data.strengths[1]").value("结果量化"))
                .andExpect(jsonPath("$.data.gaps[0]").value("缺少故障案例"))
                .andExpect(jsonPath("$.data.suggestions[1]").value("补充压测数据"))
                .andExpect(jsonPath("$.data.turnFeedbacks.length()").value(1))
                .andExpect(jsonPath("$.data.turnFeedbacks[0].question").value(QUESTION))
                .andExpect(jsonPath("$.data.turnFeedbacks[0].score").value(90))
                .andExpect(jsonPath("$.data.turnFeedbacks[0].highlight").value("量化延迟改善"))
                .andExpect(jsonPath("$.data.turnFeedbacks[0].improvement").value("补充缓存失效策略"));
        assertEquals(before, sessionData(session.sessionId()));
    }

    @Test void finishingWithoutAnswersReturnsEmptyRuleReport() throws Exception {
        var session = start(2);
        finish(session.sessionId());
        JsonNode before = sessionData(session.sessionId());
        report(session.sessionId(), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiModel").value("rule"))
                .andExpect(jsonPath("$.data.overallScore").value(0))
                .andExpect(jsonPath("$.data.overallSummary").value("还没有回答，没有报告可生成。"))
                .andExpect(jsonPath("$.data.turnFeedbacks").isEmpty())
                .andExpect(jsonPath("$.data.strengths").isEmpty());
        assertEquals(before, sessionData(session.sessionId()));
    }

    @Test void unavailableModelReturnsRuleFeedbackForOnlyAnsweredTurns() throws Exception {
        var session = start(2);
        answer(session);
        finish(session.sessionId());
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn(null);
        assertRuleReport(session.sessionId());
        assertEquals(ANSWER, sessionData(session.sessionId()).at("/turns/0/answer").asText());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reportModelFailureOrInvalidOutputFallsBackWithoutLosingSession(boolean modelThrows) throws Exception {
        var session = completed();
        JsonNode before = sessionData(session.sessionId());
        if (modelThrows) {
            when(ai.chat(anyString(), anyString(), eq(60))).thenThrow(new AiClient.AiException("测试超时"));
        } else {
            when(ai.chat(anyString(), anyString(), eq(60))).thenReturn("无法生成报告");
        }
        assertRuleReport(session.sessionId());
        assertEquals(before, sessionData(session.sessionId()));
        when(ai.chat(anyString(), anyString(), eq(60))).thenReturn(MODEL_REPORT);
        report(session.sessionId(), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiModel").value("test-model"));
    }

    @Test void reportRequestConfigurationErrorPreservesFinishedSessionForRetry() throws Exception {
        var session = completed();
        JsonNode before = sessionData(session.sessionId());
        when(resolver.resolve(any(HttpServletRequest.class))).thenThrow(new IllegalArgumentException("模型地址不正确"));
        report(session.sessionId(), owner).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("模型地址不正确"));
        assertEquals(before, sessionData(session.sessionId()));
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn(ai);
        report(session.sessionId(), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallScore").value(88));
    }

    @Test void failedNextQuestionKeepsPendingAnswerThroughReportRejectionAndFinish() throws Exception {
        var session = start(2);
        when(ai.chat(anyString(), anyString())).thenThrow(new AiClient.AiException("测试超时"));
        submit(session).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("模型调用失败，请稍后重试；当前回答已保留"));
        JsonNode pending = sessionData(session.sessionId());
        assertEquals("ACTIVE", pending.path("status").asText());
        assertEquals(ANSWER, pending.path("pendingAnswer").asText());
        assertEquals(session.currentQuestion().questionId(), pending.at("/currentQuestion/questionId").asText());
        report(session.sessionId(), owner).andExpect(status().isConflict());
        assertEquals(pending, sessionData(session.sessionId()));
        finish(session.sessionId());
        JsonNode finished = sessionData(session.sessionId());
        assertEquals("FINISHED", finished.path("status").asText());
        assertEquals(ANSWER, finished.at("/turns/0/answer").asText());
        assertEquals(1, finished.path("turns").size());
        assertTrue(finished.path("pendingAnswer").isMissingNode() || finished.path("pendingAnswer").isNull());
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn(null);
        assertRuleReport(session.sessionId());
        assertEquals(finished, sessionData(session.sessionId()));
    }

    private InterviewService.Session start(int count) {
        return interviews.start(owner, "test-material", "Java Redis 项目，负责缓存优化。", "Java 工程师", count, ai);
    }

    private InterviewService.Session completed() throws Exception {
        var session = start(1);
        answer(session);
        return session;
    }

    private ResultActions submit(InterviewService.Session session) throws Exception {
        return mvc.perform(post("/api/interviews/" + session.sessionId() + "/turns")
                .header(UserContext.HEADER, owner).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("questionId", session.currentQuestion().questionId(), "answer", ANSWER))));
    }

    private void answer(InterviewService.Session session) throws Exception {
        submit(session).andExpect(status().isOk());
    }

    private void finish(String id) throws Exception {
        mvc.perform(post("/api/interviews/" + id + "/finish").header(UserContext.HEADER, owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FINISHED"));
    }

    private ResultActions report(String id, String user) throws Exception {
        return mvc.perform(get("/api/interviews/" + id + "/report").header(UserContext.HEADER, user));
    }

    private JsonNode sessionData(String id) throws Exception {
        var result = mvc.perform(get("/api/interviews/" + id).header(UserContext.HEADER, owner))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void assertRuleReport(String id) throws Exception {
        report(id, owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiModel").value("rule"))
                .andExpect(jsonPath("$.data.overallScore").value(75))
                .andExpect(jsonPath("$.data.overallSummary").value("本次为规则模式报告，建议接入大模型获取更细致的点评。"))
                .andExpect(jsonPath("$.data.turnFeedbacks.length()").value(1))
                .andExpect(jsonPath("$.data.turnFeedbacks[0].question").value(QUESTION))
                .andExpect(jsonPath("$.data.turnFeedbacks[0].improvement").value("用「背景—行动—结果」结构重新组织，补充可验证的数字"));
    }
}
