package com.shijie.resumehelper.common.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shijie.resumehelper.common.util.TextKit;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * OpenAI 兼容协议的大模型客户端，用 JDK 自带的 {@link HttpClient} 实现，不引第三方 SDK。
 *
 * <p>DeepSeek、通义千问（兼容模式）、豆包（方舟）、以及任何自建网关都遵循
 * {@code POST {baseUrl}/chat/completions} 这套协议，所以只要改 base-url 和 model 就能换厂商。
 * 这也正是产品方案 5 要求的「业务模块不直接依赖某个模型厂商的具体请求格式」。
 *
 * <p>该类是<b>一次性</b>的：每次请求由 {@link AiClientResolver} 新建，
 * 内部持有的密钥随请求结束一起回收。静态的 HttpClient 只负责连接复用，不持有凭据。
 */
public final class OpenAiCompatibleAiClient implements AiClient {

    /**
     * 连接超时固定 15 秒；读取超时按配置。HttpClient 是线程安全的，可以全局复用。
     *
     * <p>不跟随重定向：用户自带的密钥会随请求发出，一旦目标地址被重定向，
     * 密钥就会流向我们没有校验过的地址。要重定向就直说，由配置或白名单处理。
     */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final AiCredentials credentials;
    private final AiProperties config;
    private final ObjectMapper mapper;
    private final Integer outputBudget;

    public OpenAiCompatibleAiClient(AiCredentials credentials,
                                    AiProperties config,
                                    ObjectMapper mapper) {
        this(credentials, config, mapper, null);
    }

    private OpenAiCompatibleAiClient(AiCredentials credentials,
                                     AiProperties config,
                                     ObjectMapper mapper,
                                     Integer outputBudget) {
        this.credentials = credentials;
        this.config = config;
        this.mapper = mapper;
        this.outputBudget = outputBudget;
    }

    @Override
    public AiClient withOutputBudget(int maxTokens) {
        AiClient.super.withOutputBudget(maxTokens);
        return new OpenAiCompatibleAiClient(credentials, config, mapper, maxTokens);
    }

    @Override
    public String name() {
        return credentials.model();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, config.getTimeoutSeconds());
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, int timeoutSeconds) {
        long start = System.currentTimeMillis();
        String url = endpoint();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", credentials.model());
        body.put("temperature", config.getTemperature());
        body.put("max_tokens", outputBudget == null ? config.getMaxTokens() : outputBudget);
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", TextKit.orEmpty(systemPrompt));
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", TextKit.orEmpty(userPrompt));

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Authorization", "Bearer " + credentials.apiKey())
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new AiException("大模型地址不合法：" + url, false, e,
                    AiException.Code.CONFIG, null, elapsed(start));
        }

        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new AiException("大模型调用超时（" + Math.max(5, timeoutSeconds) + " 秒）", true, e,
                    AiException.Code.TIMEOUT, null, elapsed(start));
        } catch (IOException e) {
            throw new AiException("无法连接大模型服务，请检查网络或 base-url", true, e,
                    AiException.Code.NETWORK, null, elapsed(start));
        } catch (InterruptedException e) {
            // 中断不视作可重试的临时故障：用户结束/换页触发的取消不应被当成网络失败重试。
            Thread.currentThread().interrupt();
            throw new AiException("大模型调用被中断", false, e,
                    AiException.Code.INTERRUPTED, null, elapsed(start));
        }

        checkStatus(response, start);

        String payload = response.body() == null ? "" : response.body();
        try {
            JsonNode root = mapper.readTree(payload);
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                String message = error.path("message").asText(error.toString());
                throw new AiException("大模型返回错误：" + TextKit.truncate(message, 200), false,
                        AiException.Code.UNKNOWN, null, elapsed(start));
            }
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new AiException("大模型返回内容为空", true,
                        AiException.Code.INVALID_OUTPUT, null, elapsed(start));
            }
            JsonNode message = choices.get(0).path("message");
            String content = message.path("content").asText("");
            // 只认 content。部分推理模型把思考写在 reasoning_content 里而 content 为空；
            // 回退到它会让我们把思考过程当成正式正文，甚至直接朗读出来。
            if (content.isBlank()) {
                throw new AiException("大模型没有返回可用文本", true,
                        AiException.Code.INVALID_OUTPUT, null, elapsed(start));
            }
            return content;
        } catch (JsonProcessingException e) {
            throw new AiException("大模型返回的不是合法 JSON", true, e,
                    AiException.Code.INVALID_OUTPUT, null, elapsed(start));
        }
    }

    /** 把 HTTP 状态码翻译成用户能看懂的中文提示与可追踪 code。 */
    private static void checkStatus(HttpResponse<String> response, long start) {
        int status = response.statusCode();
        String payload = response.body() == null ? "" : response.body();
        if (status == 401 || status == 403) {
            throw new AiException("API Key 无效，或该 Key 没有这个模型的权限（HTTP " + status + "）", false,
                    AiException.Code.AUTH, null, elapsed(start));
        }
        if (status == 404) {
            throw new AiException("模型或接口路径不存在，请检查 base-url 与 model（HTTP 404）", false,
                    AiException.Code.CONFIG, null, elapsed(start));
        }
        if (status == 429) {
            Long retryAfterMs = parseRetryAfter(response);
            // 不能仅凭 429 就声称额度耗尽：只有上游明确给出额度信号时才判 QUOTA。
            if (isQuotaExhausted(payload)) {
                throw new AiException("大模型调用额度受限，请检查账户余额", false,
                        AiException.Code.QUOTA, retryAfterMs, elapsed(start));
            }
            throw new AiException("大模型调用频率受限，请稍后重试", true,
                    AiException.Code.RATE_LIMITED, retryAfterMs, elapsed(start));
        }
        if (status >= 500) {
            throw new AiException("大模型服务暂时不可用（HTTP " + status + "）", true,
                    AiException.Code.UPSTREAM_UNAVAILABLE, null, elapsed(start));
        }
        if (status < 200 || status >= 300) {
            throw new AiException("大模型返回异常状态 HTTP " + status + "：" + TextKit.truncate(payload, 200), true,
                    AiException.Code.UNKNOWN, null, elapsed(start));
        }
    }

    private static long elapsed(long start) {
        return Math.max(0L, System.currentTimeMillis() - start);
    }

    /**
     * 解析 {@code Retry-After}：支持秒数与 HTTP 日期，无法识别时返回 {@code null}。
     * 不信任负值或过大的历史日期，统一夹到 {@code >= 0}。
     */
    private static Long parseRetryAfter(HttpResponse<?> response) {
        String header = response.headers().firstValue("Retry-After").orElse(null);
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        try {
            return Math.max(0L, Long.parseLong(value) * 1000L);
        } catch (NumberFormatException notSeconds) {
            // 不是秒数，再按 HTTP 日期尝试。
        }
        try {
            var when = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            return Math.max(0L, java.time.Duration.between(java.time.Instant.now(), when.toInstant()).toMillis());
        } catch (Exception notDate) {
            return null;
        }
    }

    /** 仅在出现明确额度耗尽信号时判定为 QUOTA，避免把临时限流误判成额度问题。 */
    private static boolean isQuotaExhausted(String payload) {
        if (payload == null || payload.isBlank()) {
            return false;
        }
        String lower = payload.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("insufficient_quota")
                || lower.contains("insufficient balance")
                || lower.contains("quota exceeded")
                || lower.contains("exceeded your current quota")
                || lower.contains("余额不足")
                || lower.contains("额度已用尽")
                || lower.contains("额度不足");
    }

    /** 拼出 chat/completions 地址，兼容用户把完整路径直接填进 base-url 的情况。 */
    private String endpoint() {
        String base = credentials.baseUrl();
        if (base.isEmpty()) {
            base = "https://api.deepseek.com/v1";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.contains("/chat/completions") ? base : base + "/chat/completions";
    }
}
