package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T01：InterviewFailure 是面向前端的可追踪错误记录，必须按 code 区分来源、
 * 不得携带密钥、简历、回答或上游原始正文。
 */
class InterviewFailureTest {

    @Test void mapsTimeoutFailureWithSafeMessage() {
        // 技术消息里的「5 秒」属于诊断细节，不应进入面向前端的安全消息。
        var e = new AiClient.AiException("大模型调用超时（5 秒）", true,
                AiClient.AiException.Code.TIMEOUT, null, 1234L);
        var failure = InterviewFailure.from(e);

        assertEquals(InterviewFailure.Code.TIMEOUT, failure.code());
        assertTrue(failure.retryable());
        assertNull(failure.retryAfterMs());
        assertFalse(failure.message().contains("5 秒"), "安全消息不应回显技术细节");
        assertFalse(failure.message().isBlank());
    }

    @Test void mapsQuotaAsNonRetryable() {
        var e = new AiClient.AiException("额度受限", false,
                AiClient.AiException.Code.QUOTA, null, 0L);
        var failure = InterviewFailure.from(e);

        assertEquals(InterviewFailure.Code.QUOTA, failure.code());
        assertFalse(failure.retryable());
    }

    @Test void preservesRetryAfterMsForRateLimited() {
        var e = new AiClient.AiException("限流", true,
                AiClient.AiException.Code.RATE_LIMITED, 5000L, 10L);
        var failure = InterviewFailure.from(e);

        assertEquals(InterviewFailure.Code.RATE_LIMITED, failure.code());
        assertTrue(failure.retryable());
        assertEquals(Long.valueOf(5000L), failure.retryAfterMs());
    }

    @Test void legacyConstructorFallsBackToUnknown() {
        // 旧构造器（jobs/materials 等模块仍用）不提供 code，必须降级为 UNKNOWN 而不是抛错。
        var e = new AiClient.AiException("旧调用失败", false);
        var failure = InterviewFailure.from(e);

        assertEquals(InterviewFailure.Code.UNKNOWN, failure.code());
        assertFalse(failure.retryable());
        assertNull(failure.retryAfterMs());
    }

    @Test void messageNeverLeaksUpstreamPayload() {
        // AiException 的技术消息可能含截断上游正文，InterviewFailure 必须用安全文案替代。
        var e = new AiClient.AiException("大模型返回异常状态 HTTP 502：{\"sensitive\":\"upstream body\"}",
                true, AiClient.AiException.Code.UNKNOWN, null, 5L);
        var failure = InterviewFailure.from(e);

        assertFalse(failure.message().contains("sensitive"), "不得回显上游正文");
        assertFalse(failure.message().contains("502"), "安全消息不带原始状态码");
    }
}
