package com.shijie.resumehelper.job.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.job.config.JobModuleProperties;
import com.shijie.resumehelper.job.util.TextKit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 按请求构造大模型客户端。
 *
 * <p>为什么不是 Spring 单例 Bean：用户自带 token 意味着
 * <b>每个请求的密钥都可能不同</b>。把带密钥的客户端注册成单例，
 * 就会出现「A 用户的请求用了 B 用户的 key」这类串号问题。
 * 这里每次调用都新建一个轻量客户端（内部复用的是静态的 HttpClient）。
 *
 * <p>密钥来源优先级：请求头 &gt; 服务端配置。
 */
@Component
public class AiClientResolver {

    /** 用户自己的 API Key。 */
    public static final String HEADER_API_KEY = "X-AI-Api-Key";
    /** 用户自定义的 OpenAI 兼容端点，例如自建网关。 */
    public static final String HEADER_BASE_URL = "X-AI-Base-Url";
    /** 用户选择的模型名。 */
    public static final String HEADER_MODEL = "X-AI-Model";

    private final JobModuleProperties properties;
    private final ObjectMapper mapper;

    public AiClientResolver(JobModuleProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    /** 从请求头 + 服务端配置解析出本次调用使用的凭据。绝不记录 apiKey 明文。 */
    public AiCredentials fromRequest(HttpServletRequest request) {
        JobModuleProperties.Ai config = properties.getAi();

        String headerKey = request == null ? "" : TextKit.trim(request.getHeader(HEADER_API_KEY));
        String apiKey = headerKey.isEmpty() ? TextKit.trim(config.getApiKey()) : headerKey;
        if (apiKey.isEmpty()) {
            return AiCredentials.none();
        }
        // A caller may choose a destination only for their own key, never for a server secret.
        String baseUrl = headerKey.isEmpty() ? config.getBaseUrl()
                : firstNonBlank(request.getHeader(HEADER_BASE_URL), config.getBaseUrl());
        String model = firstNonBlank(request == null ? "" : request.getHeader(HEADER_MODEL), config.getModel());
        return new AiCredentials(apiKey, baseUrl, model);
    }

    /**
     * 构造大模型客户端。
     *
     * @return 可用的客户端；返回 {@code null} 表示本次没有可用密钥，
     *         调用方应降级为 {@link FakeAiClient}（规则检索 + 规则解释），而不是报错给用户
     */
    public AiClient resolve(HttpServletRequest request) {
        return resolve(fromRequest(request));
    }

    public AiClient resolve(AiCredentials credentials) {
        if (credentials == null || !credentials.usable()) {
            return null;
        }
        return new OpenAiCompatibleAiClient(credentials, properties.getAi(), mapper);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        String value = TextKit.trim(preferred);
        return value.isEmpty() ? TextKit.trim(fallback) : value;
    }
}
