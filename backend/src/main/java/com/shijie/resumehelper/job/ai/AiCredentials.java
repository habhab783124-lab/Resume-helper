package com.shijie.resumehelper.job.ai;

import com.shijie.resumehelper.job.util.TextKit;

/**
 * 一次请求使用的大模型凭据。
 *
 * <p>用户可以在前端填写自己的 token（对应 magic-resume 的「自定义模型」思路），
 * 也可以由服务端在配置里提供兜底密钥。优先级：请求头 &gt; 服务端配置。
 *
 * <p><b>安全约定</b>：这个对象只存在于单次请求的调用链里，不写入数据库、不打日志。
 * {@link #maskedKey()} 是唯一允许出现在日志里的形式。
 */
public record AiCredentials(String apiKey, String baseUrl, String model) {

    public AiCredentials {
        apiKey = TextKit.trim(apiKey);
        baseUrl = TextKit.trim(baseUrl);
        model = TextKit.trim(model);
    }

    /** 没有可用密钥。 */
    public static AiCredentials none() {
        return new AiCredentials("", "", "");
    }

    /** 是否有可用的 API Key。 */
    public boolean usable() {
        return !apiKey.isEmpty();
    }

    /** 打日志用的脱敏形式，例如 {@code sk-abc***xyz}。 */
    public String maskedKey() {
        if (apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
    }
}
