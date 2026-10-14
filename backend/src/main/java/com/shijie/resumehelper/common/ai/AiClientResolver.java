package com.shijie.resumehelper.common.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.util.TextKit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Locale;

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

    private final AiProperties properties;
    private final ObjectMapper mapper;

    public AiClientResolver(AiProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    /** 从请求头 + 服务端配置解析出本次调用使用的凭据。绝不记录 apiKey 明文。 */
    public AiCredentials fromRequest(HttpServletRequest request) {
        AiProperties config = properties;

        String headerKey = request == null ? "" : TextKit.trim(request.getHeader(HEADER_API_KEY));
        String apiKey = headerKey.isEmpty() ? TextKit.trim(config.getApiKey()) : headerKey;
        if (apiKey.isEmpty()) {
            return AiCredentials.none();
        }
        // 服务端密钥始终发往服务端配置的地址，调用方无从改写。
        // 用户自己的密钥允许指定地址，但目的地址必须在登记清单里。
        String requestedBaseUrl = request == null ? "" : TextKit.trim(request.getHeader(HEADER_BASE_URL));
        String baseUrl = headerKey.isEmpty() || requestedBaseUrl.isEmpty()
                ? config.getBaseUrl() : requestedBaseUrl;
        if (!headerKey.isEmpty() && !requestedBaseUrl.isEmpty()) {
            validateBrowserEndpoint(requestedBaseUrl, config.getAllowedBrowserOrigins());
        }
        String model = firstNonBlank(request == null ? "" : request.getHeader(HEADER_MODEL), config.getModel());
        return new AiCredentials(apiKey, baseUrl, model);
    }

    /**
     * 校验浏览器提供的模型地址：只允许 HTTPS、无 userinfo/query/fragment，
     * 且 host+port 命中登记清单。
     *
     * <p>为什么必须卡这一层：密钥是我们替用户发出去的，但目的地址由请求方决定。
     * 不校验的话，任意非空用户密钥配一个自定义地址，就能驱动后端去请求环回或内网
     * 服务——等于开了个 SSRF 入口。管理员的服务端配置不经此校验，本地测试服务器照常可用。
     *
     * @throws IllegalArgumentException 地址非法或未登记；消息不含用户输入，避免回显
     */
    private static void validateBrowserEndpoint(String url, List<String> allowedOrigins) {
        String target = normalizeOrigin(url);
        if (target == null) {
            throw new IllegalArgumentException("模型地址须为已登记的 HTTPS 地址");
        }
        boolean allowed = allowedOrigins.stream().anyMatch(origin -> target.equals(normalizeOrigin(origin)));
        if (!allowed) {
            throw new IllegalArgumentException("模型服务地址未登记，请联系部署者配置可信网关");
        }
    }

    /** 归一化成小写 {@code host:port}；格式非法或不符合要求时返回 {@code null}。 */
    private static String normalizeOrigin(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getRawQuery() != null) {
            return null;
        }
        int port = uri.getPort() < 0 ? 443 : uri.getPort();
        return uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
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
        return new OpenAiCompatibleAiClient(credentials, properties, mapper);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        String value = TextKit.trim(preferred);
        return value.isEmpty() ? TextKit.trim(fallback) : value;
    }
}
