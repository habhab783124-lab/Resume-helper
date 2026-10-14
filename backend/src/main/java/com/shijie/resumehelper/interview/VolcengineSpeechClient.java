package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 火山豆包语音适配。
 *
 * <p>协议细节（2026-10-13 真实账户实测，见 backend/docs/语音面试接口与接入.md）：
 * <ul>
 *   <li>TTS 是逐行 JSON：多个 {@code code:0}+{@code data} 音频片段、{@code sentence}
 *       元数据片段，最后以 {@code code:20000000} 结束；</li>
 *   <li>业务错误在响应体的 {@code header} 对象里，不在顶层；TTS 的 403 不带
 *       {@code X-Api-Status-Code} 头，ASR 会同时给头和 body；</li>
 *   <li>ASR 的 {@code audio_info.duration} 单位是毫秒。</li>
 * </ul>
 *
 * <p>端点是代码常量，不接受浏览器或环境变量传入任意 URL——
 * 那里一旦开放就成了 SSRF 入口（岗位模块的模型端点踩过这个坑）。
 * 日志只留业务码、requestId 和 logid，不回传上游错误全文、不记录密钥。
 */
@Service
public class VolcengineSpeechClient implements SpeechClient {

    private static final Logger log = LoggerFactory.getLogger(VolcengineSpeechClient.class);

    static final String DEFAULT_BASE_URL = "https://openspeech.bytedance.com";
    static final String ASR_PATH = "/api/v3/auc/bigmodel/recognize/flash";
    static final String TTS_PATH = "/api/v3/tts/unidirectional";

    private static final int SUCCESS_CODE = 20000000;
    /** 实测：1 字节非法音频返回该码，说明格式校验在鉴权与授权之后生效。 */
    private static final int AUDIO_NOT_DECODABLE = 45000151;
    /** 资源未授权 / 额度用尽：用户重试不会变好，得联系部署者。 */
    private static final int RESOURCE_NOT_GRANTED = 45000030;
    private static final int QUOTA_EXCEEDED = 45000292;
    private static final String NOT_CONFIGURED = "语音服务未配置，文字面试仍可继续使用";
    private static final String UNAVAILABLE = "语音服务暂时不可用，请重试";
    private static final String PERMANENT_FAILURE = "语音服务不可用，请联系部署者检查资源授权或额度";
    private static final int MAX_ASR_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_SYNTH_BYTES = 5 * 1024 * 1024;
    private static final String SYNTH_CONTENT_TYPE = "audio/mpeg";

    private static final HttpClient FIXED_HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final SpeechProperties properties;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final HttpClient http;

    @Autowired
    public VolcengineSpeechClient(SpeechProperties properties, ObjectMapper mapper) {
        this(properties, mapper, DEFAULT_BASE_URL, FIXED_HTTP);
    }

    /** 测试专用：换成本机 stub 的 base URL 与自建 HttpClient。 */
    VolcengineSpeechClient(SpeechProperties properties, ObjectMapper mapper,
                           String baseUrl, HttpClient http) {
        this.properties = properties;
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.http = http;
    }

    @Override
    public Transcript transcribe(byte[] wav, String requestId) {
        if (wav == null || wav.length == 0) {
            throw new InterviewException(400, "录音内容为空");
        }
        requireConfigured(requestId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + ASR_PATH))
                .timeout(Duration.ofSeconds(properties.getAsrTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", properties.getApiKey())
                .header("X-Api-Resource-Id", properties.getAsrResourceId())
                .header("X-Api-Request-Id", requestId)
                .header("X-Api-Sequence", "-1")
                .POST(HttpRequest.BodyPublishers.ofByteArray(toJson(asrBody(wav))))
                .build();

        long startedAtNanos = System.nanoTime();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterviewException(504, "语音识别中断，请重试");
        } catch (HttpTimeoutException e) {
            log.warn("ASR 调用超时 requestId={}", requestId);
            throw new InterviewException(504, "语音识别超时，请缩短录音后重试");
        } catch (IOException e) {
            log.warn("ASR 请求失败 requestId={} error={}", requestId, e.getMessage());
            throw new InterviewException(504, "语音服务暂时不可用，请重试");
        }

        InputStream body = response.body();
        try {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("ASR HTTP {} requestId={}", response.statusCode(), requestId);
                throw upstreamFailure(response.statusCode());
            }
            byte[] payload = readAsrBody(body, requestId,
                    remainingBudget(startedAtNanos, properties.getAsrTimeoutSeconds()));
            if (payload == null) {
                log.warn("ASR 响应超过 {} 字节上限 requestId={}", MAX_ASR_RESPONSE_BYTES, requestId);
                throw new InterviewException(502, "语音服务返回异常，请重试");
            }
            JsonNode root = readTree(payload, requestId);
            // HTTP 200 不代表识别成功。业务状态优先取响应头；
            // 实测有些错误（TTS 的 403）不带这个头，那种情况退回响应体的 header 字段，
            // 不要因为缺头就丢掉已经拿到的诊断信息。
            String headerCode = response.headers().firstValue("X-Api-Status-Code").orElse("");
            int code = statusCodeFromResponse(headerCode, root);
            if (code != SUCCESS_CODE) {
                log.warn("ASR 业务失败 code={} 头={} requestId={} logid={}", code, headerCode, requestId,
                        response.headers().firstValue("X-Tt-Logid").orElse(""));
                throw asrBusinessFailure(code);
            }
            String text = root.path("result").path("text").asText("").trim();
            long durationMs = root.path("audio_info").path("duration").asLong(0);
            if (text.isEmpty()) {
                log.warn("ASR 未识别到文字 requestId={} durationMs={}", requestId, durationMs);
                throw new InterviewException(422, "没有识别到有效语音，请重新录音");
            }
            return new Transcript(text, durationMs);
        } finally {
            closeQuietly(body);
        }
    }

    @Override
    public SpeechAudio synthesize(String text, String requestId) {
        if (text == null || text.isBlank()) {
            throw new InterviewException(400, "没有可朗读的文字");
        }
        requireConfigured(requestId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + TTS_PATH))
                .timeout(Duration.ofSeconds(properties.getTtsTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", properties.getApiKey())
                .header("X-Api-Resource-Id", properties.getTtsResourceId())
                .header("X-Api-Request-Id", requestId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(toJson(ttsBody(text))))
                .build();

        // HttpRequest.timeout 只覆盖到拿到响应头为止。deadline 必须从发起请求就算起，
        // 否则响应头慢一次，合法音频流就会被读流阶段误判成超时。
        long startedAtNanos = System.nanoTime();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterviewException(504, "题目语音生成中断，请重试");
        } catch (HttpTimeoutException e) {
            log.warn("TTS 请求超时 requestId={}", requestId);
            throw new InterviewException(504, "题目语音生成超时，请重试");
        } catch (IOException e) {
            log.warn("TTS 请求失败 requestId={} error={}", requestId, e.getMessage());
            throw new InterviewException(502, "题目语音生成失败，请稍后重试");
        }

        InputStream stream = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            closeQuietly(stream);
            log.warn("TTS HTTP {} requestId={}", response.statusCode(), requestId);
            throw upstreamFailure(response.statusCode());
        }
        if (stream == null) {
            throw new InterviewException(502, "题目语音生成失败，请稍后重试");
        }
        return readTts(stream, requestId, remainingBudget(startedAtNanos, properties.getTtsTimeoutSeconds()));
    }

    // ------------------------------------------------------------------
    // TTS 音频流
    // ------------------------------------------------------------------

    /**
     * {@code HttpRequest.timeout} + {@code BodyHandlers.ofInputStream} 只限制拿到响应头的时长，
     * 之后的流读取是无界的。消费阶段单独起一个线程并施加整次调用的剩余预算，
     * 超时就关闭流并放弃这个半成品。
     */
    private SpeechAudio readTts(InputStream stream, String requestId, Duration deadline) {
        ExecutorService reader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tts-stream-reader");
            thread.setDaemon(true);
            return thread;
        });
        Future<SpeechAudio> task = null;
        try {
            task = reader.submit(() -> consumeTts(stream));
            return task.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("TTS 音频流消费超时 requestId={}", requestId);
            if (task != null) {
                task.cancel(true);
            }
            closeQuietly(stream);
            throw new InterviewException(504, "题目语音生成超时，请重试");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof InterviewException expected) {
                throw expected;
            }
            log.warn("TTS 返回无法解析的音频流 requestId={} error={}", requestId, e.getCause());
            throw new InterviewException(502, "题目语音生成失败，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(stream);
            throw new InterviewException(504, "题目语音生成中断，请重试");
        } finally {
            reader.shutdownNow();
        }
    }

    /** 包内可见：测试需要直接喂入按任意边界切分的字节流。 */
    SpeechAudio consumeTts(InputStream stream) {
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        boolean complete = false;
        try (MappingIterator<JsonNode> parts = mapper.readerFor(JsonNode.class).readValues(stream)) {
            while (parts.hasNextValue()) {
                JsonNode part = parts.nextValue();
                int code = businessCode(part);
                String data = part.path("data").asText("");
                if (code == SUCCESS_CODE) {
                    complete = true;
                    break;
                }
                // 不带音频的片段（sentence 元数据、或连 code 都没有的片段）直接跳过：
                // 它没有携带音频，不该让整个题目合成失败。
                if (data.isEmpty()) {
                    continue;
                }
                // 带音频却给不出业务状态码（code=-1）就拒绝——
                // 无法判断成功与否，宁可不合成也不能交出可疑音频。
                if (code != 0) {
                    log.warn("TTS 业务失败 code={}", code);
                    throw ttsBusinessFailure(code);
                }
                byte[] bytes = decodeAudioData(data);
                if ((long) audio.size() + bytes.length > MAX_SYNTH_BYTES) {
                    throw new InterviewException(502, "题目音频超过大小限制");
                }
                audio.writeBytes(bytes);
            }
        } catch (IOException e) {
            throw new InterviewException(502, "题目语音生成失败，请稍后重试");
        }
        if (!complete || audio.size() == 0) {
            log.warn("TTS 未完整结束 complete={} audioBytes={}", complete, audio.size());
            throw new InterviewException(502, "题目语音未完整生成，请重试");
        }
        return new SpeechAudio(audio.toByteArray(), SYNTH_CONTENT_TYPE);
    }

    // ------------------------------------------------------------------
    // 请求与响应
    // ------------------------------------------------------------------

    private static InterviewException upstreamFailure(int status) {
        if (status == 401 || status == 403) {
            return new InterviewException(502, "语音服务未授权或密钥无效，请检查配置");
        }
        return new InterviewException(502, UNAVAILABLE);
    }

    /**
     * key 留空即语音能力关闭。不能拿空凭据发真实请求——那样用户会一直收到
     * 一个改不了的 502，而问题其实要靠部署者配环境变量。
     */
    private void requireConfigured(String requestId) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            log.warn("{}：未配置语音服务密钥，本次调用未发出", requestId);
            throw new InterviewException(503, NOT_CONFIGURED);
        }
    }

    /** ASR 同样必须限制响应头到达之后的读取，避免占住 owner 与全局许可。 */
    private byte[] readAsrBody(InputStream stream, String requestId, Duration deadline) {
        ExecutorService reader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "asr-response-reader");
            thread.setDaemon(true);
            return thread;
        });
        Future<byte[]> task = reader.submit(() -> readLimited(stream, MAX_ASR_RESPONSE_BYTES));
        try {
            return task.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("ASR 响应读取超时 requestId={}", requestId);
            task.cancel(true);
            closeQuietly(stream);
            throw new InterviewException(504, "语音识别超时，请缩短录音后重试");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof InterviewException expected) throw expected;
            throw new InterviewException(502, "语音服务返回异常，请重试");
        } catch (InterruptedException e) {
            task.cancel(true);
            closeQuietly(stream);
            Thread.currentThread().interrupt();
            throw new InterviewException(504, "语音识别中断，请重试");
        } finally {
            reader.shutdownNow();
        }
    }

    private Duration remainingBudget(long startedAtNanos, int timeoutSeconds) {
        long total = Duration.ofSeconds(timeoutSeconds).toNanos();
        long elapsed = System.nanoTime() - startedAtNanos;
        return Duration.ofNanos(Math.max(total - elapsed, 0));
    }

    private static InterviewException asrBusinessFailure(int code) {
        return switch (code) {
            case AUDIO_NOT_DECODABLE -> new InterviewException(415, "无法识别这段录音，请重新录音");
            case RESOURCE_NOT_GRANTED, QUOTA_EXCEEDED -> new InterviewException(502, PERMANENT_FAILURE);
            default -> new InterviewException(502, UNAVAILABLE);
        };
    }

    private static InterviewException ttsBusinessFailure(int code) {
        if (code == RESOURCE_NOT_GRANTED || code == QUOTA_EXCEEDED) {
            return new InterviewException(502, "题目语音合成暂不可用，请联系部署者检查资源授权或额度");
        }
        return new InterviewException(502, "题目语音生成失败，请稍后重试");
    }

    /** 业务状态优先取响应头；实测有些错误不带这个头，退回响应体的 header 字段。 */
    private static int statusCodeFromResponse(String headerCode, JsonNode body) {
        if (headerCode == null || headerCode.isBlank()) {
            return businessCode(body);
        }
        try {
            return Integer.parseInt(headerCode.trim());
        } catch (NumberFormatException e) {
            return businessCode(body);
        }
    }

    private static byte[] decodeAudioData(String data) {
        try {
            return Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new InterviewException(502, "语音服务返回无效音频");
        }
    }

    private ObjectNode asrBody(byte[] wav) {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("user").put("uid", "resume-helper");
        body.putObject("audio").put("data", Base64.getEncoder().encodeToString(wav));
        body.putObject("request").put("model_name", "bigmodel");
        return body;
    }

    private ObjectNode ttsBody(String text) {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode params = body.putObject("req_params");
        params.put("text", text);
        params.put("speaker", properties.getTtsSpeaker());
        params.putObject("audio_params").put("format", "mp3").put("sample_rate", 24000);
        return body;
    }

    private byte[] toJson(ObjectNode body) {
        try {
            return mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new InterviewException(502, "语音请求构造失败");
        }
    }

    /** 有界读取：超过 maxBytes 立即停并返回 null，不把整个响应体留在内存里再拒绝。 */
    private static byte[] readLimited(InputStream in, int maxBytes) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > maxBytes) {
                    return null;
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new InterviewException(502, "语音服务返回异常，请重试");
        }
    }

    private JsonNode readTree(byte[] payload, String requestId) {
        try {
            return mapper.readTree(payload);
        } catch (IOException e) {
            log.warn("ASR 响应不是合法 JSON requestId={}", requestId);
            throw new InterviewException(502, "语音服务返回异常，请重试");
        }
    }

    /** 业务码可能在顶层（TTS 音频流），也可能在 {@code header} 里（错误响应体）。 */
    private static int businessCode(JsonNode node) {
        JsonNode code = node.path("code");
        if (code.isNumber()) {
            return code.asInt();
        }
        code = node.path("header").path("code");
        return code.isNumber() ? code.asInt() : -1;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            log.debug("关闭语音流失败：{}", e.getMessage());
        }
    }
}
