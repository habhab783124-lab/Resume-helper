package com.shijie.resumehelper.job.ai;

/**
 * 大模型调用抽象。
 *
 * <p>产品方案 5「AI 服务封装」要求：业务模块只调用统一接口，不直接依赖某个模型厂商的请求格式。
 * 岗位模块只用到「给一段提示词、拿回一段文本」这一种能力，所以接口保持最小。
 *
 * <p><b>密钥处理原则</b>：
 * <ul>
 *   <li>用户的 token 只存在于单次请求的调用链里，不写日志、不持久化、不进全局单例。</li>
 *   <li>每个用户用自己的 key，所以 {@code AiClient} 必须按请求创建，
 *       实现类不能是 Spring 单例 Bean。</li>
 * </ul>
 */
public interface AiClient {

    /**
     * 单轮对话补全。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 模型返回的纯文本内容
     * @throws AiException 网络失败、鉴权失败、超时或额度不足
     */
    String chat(String systemPrompt, String userPrompt);

    /**
     * 指定超时（秒）的单轮补全，供连通性检查这类短请求使用。
     * 默认退回无超时参数的版本；真正发请求的实现按需覆盖。
     */
    default String chat(String systemPrompt, String userPrompt, int timeoutSeconds) {
        return chat(systemPrompt, userPrompt);
    }

    /** 模型标识，用于在智能体执行轨迹里展示，不包含密钥。 */
    default String name() {
        return "ai";
    }

    /** 该客户端当前是否可用。 */
    default boolean available() {
        return true;
    }

    /**
     * 是否为「降级替身」。
     * 用户没配置 token、或大模型调用失败时，服务层会注入离线替身，
     * 智能体据此在执行轨迹里如实标注「本次未使用大模型」。
     */
    default boolean isFallback() {
        return false;
    }

    /** AI 调用失败。业务层据此降级到规则逻辑，而不是把异常抛给前端。 */
    class AiException extends RuntimeException {
        private final boolean retryable;

        public AiException(String message) {
            this(message, true, null);
        }

        public AiException(String message, boolean retryable) {
            this(message, retryable, null);
        }

        public AiException(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }

        /**
         * 是否值得重试。
         * 鉴权失败、模型不存在属于配置问题，重试没有意义，返回 false；
         * 超时、限流、5xx 属于临时故障，返回 true。
         */
        public boolean retryable() {
            return retryable;
        }
    }
}
