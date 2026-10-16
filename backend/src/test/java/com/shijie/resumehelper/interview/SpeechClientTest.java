package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用只监听 127.0.0.1 的本地服务模拟火山，验证真实的请求构造与协议解析。
 *
 * <p>这里测的是协议边界，不是音频质量：本地服务返回的是精心构造的
 * 成功 / 失败 / 半截响应，不会产生任何费用。
 */
class SpeechClientTest {

    private static final String ASR_PATH = "/api/v3/auc/bigmodel/recognize/flash";
    private static final String TTS_PATH = "/api/v3/tts/unidirectional";

    private HttpServer server;
    private VolcengineSpeechClient client;
    private SpeechProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new SpeechProperties();
        properties.setApiKey("test-only-key");
        properties.setAsrResourceId("volc.seedasr.sauc.duration");
        properties.setTtsSpeaker("zh_female_vv_uranus_bigtts");
        // 默认给足余量，只有专门测超时的两个用例才把超时调到 1 秒。
        properties.setAsrTimeoutSeconds(10);
        properties.setTtsTimeoutSeconds(10);
        client = new VolcengineSpeechClient(properties, mapper,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(2))
                        .build());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ------------------------------------------------------------------
    // TTS：逐行 JSON 音频流
    // ------------------------------------------------------------------

    @Test
    void ttsMergesAudioFragmentsAndStopsAtSuccessCode() {
        var stream = new AtomicReference<String>();
        var requestId = new AtomicReference<String>();
        serveTts(stream, requestId,
                "{\"code\":0,\"data\":\"AQI=\"}\n" +
                "{\"code\":0,\"data\":\"AwQ=\",\"sentence\":{\"text\":\"请介绍你的项目\"}}\n" +
                "{\"code\":0}\n" +
                "{\"code\":20000000,\"data\":null}\n");

        var audio = client.synthesize("请介绍你如何用 Redis 优化 Spring Boot 项目的缓存。", "req-tts-1");

        assertArrayEquals(new byte[]{1, 2, 3, 4}, audio.bytes());
        assertEquals("audio/mpeg", audio.contentType());

        JsonNode sent = readJson(stream);
        assertEquals("请介绍你如何用 Redis 优化 Spring Boot 项目的缓存。", sent.at("/req_params/text").asText());
        assertEquals("zh_female_vv_uranus_bigtts", sent.at("/req_params/speaker").asText());
        assertEquals("mp3", sent.at("/req_params/audio_params/format").asText());
        assertEquals(24000, sent.at("/req_params/audio_params/sample_rate").asInt());
        assertEquals("req-tts-1", requestId.get());
    }

    @Test
    void ttsDecodingDoesNotDependOnReadBoundaries() {
        // JSON 对象被切在任意 read 边界上，都必须能拼出来。
        byte[] stream = (
                "{\"code\":0,\"data\":\"AQI=\"}\n" +
                "{\"code\":0,\"data\":\"AwQ=\",\"sentence\":{\"text\":\"请介绍你的项目\"}}\n" +
                "{\"code\":20000000,\"data\":null}\n").getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(new byte[]{1, 2, 3, 4}, client.consumeTts(trickle(stream, 1)).bytes(),
                "每次 read 只给 1 字节");
        assertArrayEquals(new byte[]{1, 2, 3, 4}, client.consumeTts(trickle(stream, 10_000)).bytes(),
                "所有对象落在同一次 read 里");
    }

    @Test
    void ttsRejectsStreamWithoutEndEvent() {
        serveTts(new AtomicReference<>(), new AtomicReference<>(), "{\"code\":0,\"data\":\"AQI=\"}\n");

        var thrown = assertThrows(InterviewException.class, () -> client.synthesize("题目", "req-tts-3"));
        assertEquals(502, thrown.status());
        assertFalse(thrown.getMessage().contains("AQI="), "不能把半成品当成功返回");
    }

    @Test
    void ttsRejectsBusinessErrorCode() {
        serveTts(new AtomicReference<>(), new AtomicReference<>(),
                "{\"code\":20000002,\"message\":\"internal error\"}\n");

        var thrown = assertThrows(InterviewException.class, () -> client.synthesize("题目", "req-tts-4"));
        assertEquals(502, thrown.status());
        assertFalse(thrown.getMessage().contains("internal error"), "不能回传上游错误全文");
    }

    @Test
    void ttsRejectsErrorNestedUnderHeader() {
        // 实测：TTS 的业务错误在 header 对象里，且 403 不带 X-Api-Status-Code 头。
        serveTts(new AtomicReference<>(), new AtomicReference<>(),
                "{\"header\":{\"code\":45000030,\"message\":\"resource_id requested resource not granted\"}}\n");

        assertEquals(502, assertThrows(InterviewException.class,
                () -> client.synthesize("题目", "req-tts-5")).status());
    }

    @Test
    void ttsRejectsEndEventWithoutAnyAudio() {
        serveTts(new AtomicReference<>(), new AtomicReference<>(),
                "{\"code\":0,\"data\":null}\n{\"code\":20000000}\n");

        assertEquals(502, assertThrows(InterviewException.class,
                () -> client.synthesize("题目", "req-tts-6")).status());
    }

    @Test
    void ttsRejectsMalformedBase64() {
        serveTts(new AtomicReference<>(), new AtomicReference<>(),
                "{\"code\":0,\"data\":\"!!!not-base64!!!\"}\n{\"code\":20000000}\n");

        assertEquals(502, assertThrows(InterviewException.class,
                () -> client.synthesize("题目", "req-tts-7")).status());
    }

    @Test
    void ttsRejectsAudioOverTheTotalSizeLimit() {
        String oversized = Base64.getEncoder().encodeToString(new byte[5 * 1024 * 1024 + 1]);
        serveTts(new AtomicReference<>(), new AtomicReference<>(),
                "{\"code\":0,\"data\":\"" + oversized + "\"}\n{\"code\":20000000}\n");

        assertEquals(502, assertThrows(InterviewException.class,
                () -> client.synthesize("题目", "req-tts-8")).status());
    }

    @Test
    void ttsSendsResourceKeyAndRequestIdHeaders() {
        var resource = new AtomicReference<String>();
        var key = new AtomicReference<String>();
        var contentType = new AtomicReference<String>();
        server.createContext(TTS_PATH, exchange -> {
            resource.set(exchange.getRequestHeaders().getFirst("X-Api-Resource-Id"));
            key.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            sendJson(exchange, "{\"code\":0,\"data\":\"AQI=\"}\n{\"code\":20000000}\n");
        });

        client.synthesize("题目", "req-tts-9");

        assertEquals("seed-tts-2.0", resource.get());
        assertEquals("test-only-key", key.get());
        assertTrue(contentType.get().contains("application/json"));
    }

    @Test
    void ttsSendsTheKeyResolvedForThisRequest() {
        // 服务端配置的是另一把 key：本次调用实际发出的必须是按请求注入的那把。
        properties.setApiKey("server-key");
        var wireKey = new AtomicReference<String>();
        server.createContext(TTS_PATH, exchange -> {
            wireKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
            sendJson(exchange, "{\"code\":0,\"data\":\"AQI=\"}\n{\"code\":20000000}\n");
        });

        clientWithKey("user-key").synthesize("题目", "req-tts-key");

        assertEquals("user-key", wireKey.get());
        // 空密钥不发真实请求，也不回退到服务端密钥——能力开关得跟实际用的 key 一致。
        var empty = assertThrows(InterviewException.class,
                () -> clientWithKey("   ").synthesize("题目", "req-tts-empty"));
        assertEquals(503, empty.status());
    }

    /** 构造一个按请求注入密钥的客户端，base URL 与 HttpClient 与 {@link #startServer()} 保持一致。 */
    private VolcengineSpeechClient clientWithKey(String apiKey) {
        return new VolcengineSpeechClient(properties, mapper, apiKey,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(2))
                        .build());
    }

    @Test
    void ttsMapsStalledStreamToTimeout() {
        // 只给一个音频片段就卡住，验证消费阶段有独立的 deadline。
        properties.setTtsTimeoutSeconds(1);
        var stream = new AtomicReference<String>();
        var requestId = new AtomicReference<String>();
        server.createContext(TTS_PATH, exchange -> {
            stream.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestId.set(exchange.getRequestHeaders().getFirst("X-Api-Request-Id"));
            // 声明的长度远大于实际写出量，剩下的字节永远到不了——模拟音频流中途卡死。
            exchange.sendResponseHeaders(200, 4096);
            byte[] partial = "{\"code\":0,\"data\":\"AQI=\"}\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseBody().write(partial);
            exchange.getResponseBody().flush();
            sleepQuietly(3000);
        });

        var thrown = assertThrows(InterviewException.class, () -> client.synthesize("题目", "req-tts-10"));
        assertEquals(504, thrown.status());
    }

    // ------------------------------------------------------------------
    // ASR：单次 JSON
    // ------------------------------------------------------------------

    @Test
    void transcribeSendsWavBase64AndParsesTextAndDuration() {
        byte[] wav = wav();
        var body = new AtomicReference<String>();
        var headers = new AtomicReference<java.util.Map<String, String>>();
        serveAsr(body, headers, "20000000",
                "{\"result\":{\"text\":\"缓存击穿后如何保护数据库？\"},\"audio_info\":{\"duration\":4300}}");

        var transcript = client.transcribe(wav, "req-asr-1");

        assertEquals("缓存击穿后如何保护数据库？", transcript.text());
        assertEquals(4300L, transcript.durationMs());
        assertEquals("-1", headers.get().get("X-Api-Sequence"));
        assertEquals("req-asr-1", headers.get().get("X-Api-Request-Id"));
        assertEquals("volc.seedasr.sauc.duration", headers.get().get("X-Api-Resource-Id"));
        assertEquals("test-only-key", headers.get().get("X-Api-Key"));

        JsonNode sent = readJson(body);
        assertEquals(Base64.getEncoder().encodeToString(wav), sent.at("/audio/data").asText());
        assertEquals("bigmodel", sent.at("/request/model_name").asText());
        assertEquals("resume-helper", sent.at("/user/uid").asText());
    }

    @Test
    void transcribeRejectsSilentAudioWithoutText() {
        serveAsr(new AtomicReference<>(), new AtomicReference<>(), "20000000",
                "{\"result\":{\"text\":\"\"},\"audio_info\":{\"duration\":120000}}");

        var thrown = assertThrows(InterviewException.class, () -> client.transcribe(wav(), "req-asr-2"));
        assertEquals(422, thrown.status());
    }

    @Test
    void transcribeRejectsResponseWithoutBusinessStatusHeader() {
        serveAsr(new AtomicReference<>(), new AtomicReference<>(), null, "{\"result\":{\"text\":\"有文字\"}}");

        assertEquals(502, assertThrows(InterviewException.class,
                () -> client.transcribe(wav(), "req-asr-3")).status());
    }

    @Test
    void transcribeRejectsBusinessFailureReportedOnlyInHeader() {
        // 实测：HTTP 200 也可能带失败的业务状态，X-Api-Status-Code 与 body header 同时给。
        serveAsr(new AtomicReference<>(), new AtomicReference<>(), "45000030",
                "{\"header\":{\"code\":45000030,\"message\":\"resource_id requested resource not granted\"}}");

        var thrown = assertThrows(InterviewException.class, () -> client.transcribe(wav(), "req-asr-4"));
        assertEquals(502, thrown.status());
        assertFalse(thrown.getMessage().contains("requested resource not granted"));
    }

    @Test
    void transcribeRejectsUndecodableAudioAsUnsupportedMedia() {
        serveAsr(new AtomicReference<>(), new AtomicReference<>(), "45000151",
                "{\"header\":{\"code\":45000151,\"message\":\"The audio could not be decoded\"}}");

        assertEquals(415, assertThrows(InterviewException.class,
                () -> client.transcribe(wav(), "req-asr-5")).status());
    }

    @Test
    void transcribeRejectsOversizedResponse() {
        serveAsr(new AtomicReference<>(), new AtomicReference<>(), "20000000",
                "{\"result\":{\"text\":\"" + "x".repeat(1024 * 1024 + 1) + "\"}}");

        assertEquals(502, assertThrows(InterviewException.class,
                () -> client.transcribe(wav(), "req-asr-6")).status());
    }

    @Test
    void transcribeDoesNotFollowRedirects() {
        var reachedRedirected = new AtomicBoolean();
        server.createContext("/api/v3/auc/redirected", exchange -> {
            reachedRedirected.set(true);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext(ASR_PATH, exchange -> {
            exchange.getResponseHeaders().add("Location", "/api/v3/auc/redirected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        var thrown = assertThrows(InterviewException.class, () -> client.transcribe(wav(), "req-asr-7"));
        assertEquals(502, thrown.status());
        assertFalse(reachedRedirected.get(), "重定向没有被跟随");
    }

    @Test
    void transcribeMapsUpstreamTimeoutTo504() {
        properties.setAsrTimeoutSeconds(1);
        server.createContext(ASR_PATH, exchange -> {
            sleepQuietly(3000);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        var thrown = assertThrows(InterviewException.class, () -> client.transcribe(wav(), "req-asr-8"));
        assertEquals(504, thrown.status());
    }

    @Test
    void transcribeRejectsEmptyWavWithoutCallingUpstream() {
        var reached = new AtomicBoolean();
        server.createContext(ASR_PATH, exchange -> { reached.set(true); exchange.close(); });

        assertEquals(400, assertThrows(InterviewException.class,
                () -> client.transcribe(new byte[0], "req-asr-9")).status());
        assertFalse(reached.get(), "空录音不应发起付费调用");
    }

    @Test
    void transcribeTimesOutWhenBodyStallsAfterHeaders() {
        properties.setAsrTimeoutSeconds(1);
        server.createContext(ASR_PATH, exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"result\":{\"text\":\"识别结果\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Api-Status-Code", "20000000");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body, 0, 1);
            exchange.getResponseBody().flush();
            sleepQuietly(2000);
            try { exchange.getResponseBody().write(body, 1, body.length - 1); }
            catch (IOException ignored) { /* 客户端超时后已关闭响应。 */ }
            finally { exchange.close(); }
        });
        var thrown = assertThrows(InterviewException.class,
                () -> client.transcribe(wav(), "req-asr-stalled-body"));
        assertEquals(504, thrown.status());
    }

    // ------------------------------------------------------------------
    // 本地服务辅助
    // ------------------------------------------------------------------

    private byte[] wav() {
        return new byte[]{0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x41, 0x56, 0x45};
    }

    private JsonNode readJson(AtomicReference<String> body) {
        try {
            return mapper.readTree(body.get());
        } catch (IOException e) {
            throw new AssertionError("请求体不是合法 JSON：" + body.get(), e);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 定长响应；必须按 UTF-8 字节数而不是字符数声明长度。 */
    private static void sendJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** 返回单段 JSON，并把请求体与全部相关请求头记到 AtomicReference 里。 */
    private void serveAsr(AtomicReference<String> body, AtomicReference<java.util.Map<String, String>> headers,
                          String apiStatusCode, String responseBody) {
        server.createContext(ASR_PATH, exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            headers.set(java.util.Map.of(
                    "X-Api-Key", header(exchange, "X-Api-Key"),
                    "X-Api-Resource-Id", header(exchange, "X-Api-Resource-Id"),
                    "X-Api-Request-Id", header(exchange, "X-Api-Request-Id"),
                    "X-Api-Sequence", header(exchange, "X-Api-Sequence")));
            if (apiStatusCode != null) {
                exchange.getResponseHeaders().add("X-Api-Status-Code", apiStatusCode);
            }
            sendJson(exchange, responseBody);
        });
    }

    /** 返回整段 TTS 流，并记录请求体与 X-Api-Request-Id。 */
    private void serveTts(AtomicReference<String> body, AtomicReference<String> requestId, String stream) {
        server.createContext(TTS_PATH, exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestId.set(header(exchange, "X-Api-Request-Id"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            sendJson(exchange, stream);
        });
    }

    /** 每次 read 只返回 size 字节，用来把 JSON 对象切在任意 read 边界上。 */
    private static InputStream trickle(byte[] data, int size) {
        ByteArrayInputStream source = new ByteArrayInputStream(data);
        return new InputStream() {
            @Override
            public int read() {
                return source.read();
            }

            @Override
            public int read(byte[] destination, int offset, int length) {
                if (source.available() == 0) {
                    return -1;
                }
                return source.read(destination, offset, Math.min(size, length));
            }

            @Override
            public int available() {
                return source.available();
            }
        };
    }

    private static String header(HttpExchange exchange, String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }
}
