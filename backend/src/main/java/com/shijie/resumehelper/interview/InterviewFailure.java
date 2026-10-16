package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;

/**
 * 面试调用失败的可追踪记录，按 {@code 4.1} 携带 code、retryable、message、retryAfterMs。
 *
 * <p><b>安全边界</b>：只包含机器可读的 code 与一份针对该 code 的安全文案；
 * 不携带 API Key、简历、回答或上游原始正文。详细技术原因走日志，不进响应体。
 *
 * <p>Jackson 默认把 {@link Code} 序列化为其 {@code name()}，与前端约定的字符串 code 一致。
 */
public record InterviewFailure(Code code, boolean retryable, String message, Long retryAfterMs) {

    /** 错误来源分类，与 {@link AiClient.AiException.Code} 同义。 */
    public enum Code {
        TIMEOUT, NETWORK, RATE_LIMITED, UPSTREAM_UNAVAILABLE,
        INVALID_OUTPUT, AUTH, CONFIG, QUOTA,
        INPUT, STORAGE, INTERRUPTED, UNKNOWN
    }

    /** 由传输层 {@link AiClient.AiException} 构造，保留其 retryable 与 Retry-After。 */
    public static InterviewFailure from(AiClient.AiException e) {
        if (e == null) {
            return new InterviewFailure(Code.UNKNOWN, false, messageFor(Code.UNKNOWN), null);
        }
        Code code = map(e.code());
        return new InterviewFailure(code, e.retryable(), messageFor(code), e.retryAfterMs());
    }

    private static Code map(AiClient.AiException.Code code) {
        if (code == null) {
            return Code.UNKNOWN;
        }
        try {
            return Code.valueOf(code.name());
        } catch (IllegalArgumentException unknown) {
            return Code.UNKNOWN;
        }
    }

    /** 与 code 对应的安全文案；不回显技术细节或上游正文。 */
    private static String messageFor(Code code) {
        return switch (code) {
            case TIMEOUT -> "模型调用超时，请稍后重试";
            case NETWORK -> "无法连接模型服务，请检查网络或地址";
            case RATE_LIMITED -> "模型调用被限流，请稍后重试";
            case UPSTREAM_UNAVAILABLE -> "模型服务暂时不可用，请稍后重试";
            case INVALID_OUTPUT -> "模型返回内容异常，请重试";
            case AUTH -> "API Key 无效或缺少模型权限";
            case CONFIG -> "模型或接口配置不正确";
            case QUOTA -> "模型调用额度已用尽，请检查账户";
            case INPUT -> "请求参数无效";
            case STORAGE -> "会话存储失败";
            case INTERRUPTED -> "模型调用已中断";
            case UNKNOWN -> "模型调用失败，请稍后重试";
        };
    }
}
