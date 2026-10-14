package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.common.ai.AiClientResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 语音接口的会话归属与状态边界。
 *
 * <p>{@link AudioNormalizer} 与 {@link SpeechClient} 都是 mock：本类只验证编排——
 * 归属校验、当前题校验、并发闸门、调用前后重校验，以及「转写不推进轮次」。
 *
 * <p><b>统一材料模块后适配的两处</b>：身份从 HTTP session 改成 {@code X-User-Id} 请求头
 * （原来面试用 session id、材料用请求头，两边对不上号）；上传响应从 {@code /data/materialId}
 * 改成 {@code /data/material/id}（材料模块返回 {@code {material, extraction, text}}）。
 * 真实转码由 {@link AudioNormalizerTest} 用真工具验证，真实协议由 {@link SpeechClientTest} 验证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT) @AutoConfigureMockMvc
class InterviewSpeechApiTest {

    /**
     * 把 key 和工具路径钉死，capabilities 的断言不依赖部署机恰好装了 FFmpeg。
     *
     * <h2>为什么占位工具用 java 可执行文件，而不是原来写死的 {@code /usr/bin/env}</h2>
     * 本类里 {@code AudioNormalizer} 是 mock，这个路径只需要"存在且可执行"。
     * 原来写的是 {@code /usr/bin/env}——那是 Unix 路径，<b>Windows 上根本不存在</b>，
     * 于是 {@code toolAvailable} 返回 false、{@code asrEnabled} 变成 false，
     * 这个类的 8 个用例在 Windows 上全部失败（而且看起来像功能坏了）。
     *
     * <p>改成本次运行的 JVM 自己的可执行文件：任何平台都有、都可执行，
     * 也不依赖任何外部工具。因此这里必须用 {@code @DynamicPropertySource}
     * 而不是 {@code @TestPropertySource}——后者的值要求编译期常量，算不出运行时路径。
     */
    @org.springframework.test.context.DynamicPropertySource
    static void speechProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        String placeholder = currentJavaExecutable();
        registry.add("app.interview.speech.api-key", () -> "speech-test-key");
        registry.add("app.interview.speech.ffmpeg-path", () -> placeholder);
        registry.add("app.interview.speech.ffprobe-path", () -> placeholder);
        registry.add("app.interview.speech.max-concurrent-requests", () -> 2);
    }

    /** 当前 JVM 的可执行文件路径，各平台都存在且可执行。 */
    private static String currentJavaExecutable() {
        java.nio.file.Path home = java.nio.file.Path.of(System.getProperty("java.home"), "bin");
        for (String name : new String[]{"java", "java.exe"}) {
            java.nio.file.Path candidate = home.resolve(name);
            if (java.nio.file.Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return "java";
    }

    private static final String QUESTION = "请介绍你的项目？";
    private static final byte[] WAV = {0x52, 0x49};
    private static final byte[] SYNTH = {9, 8, 7};
    private static final String UUID_1 = "11111111-2222-4333-8444-555555555555";

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AiClientResolver resolver;
    @MockBean AudioNormalizer normalizer;
    @MockBean SpeechClient speech;

    private record Ref(String sessionId, String questionId) {}

    /**
     * 当前 {@code resolver.resolve} 被调用的次数。
     *
     * <p>用来表达「转写不再解析模型客户端」这个意图。
     * 不能写死 times(1)：材料上传统一之后，上传本身也会解析一次客户端
     * （材料模块要做结构化抽取），所以 start() 之后的基线是 2 而不是 1。
     * 记基线再比对，既表达了原意，也不会因为上传链路调整而假失败。
     */
    private int resolverInvocations() {
        return org.mockito.Mockito.mockingDetails(resolver).getInvocations().size();
    }
    private void stubModel() {
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn((s, p) -> QUESTION);
    }

    private Ref start(MockHttpSession owner) throws Exception {
        stubModel();
        var upload = mvc.perform(multipart("/api/materials")
                        .file(new MockMultipartFile("file", "cv.txt", "text/plain",
                                "Java Redis 项目经验".getBytes(StandardCharsets.UTF_8)))
                        .header("X-User-Id", owner.getId()))
                .andExpect(status().isOk()).andReturn();
        String material = json.readTree(upload.getResponse().getContentAsString()).at("/data/material/id").asText();
        MvcResult started = mvc.perform(post("/api/interviews").header("X-User-Id", owner.getId()).contentType("application/json")
                        .content("{\"materialId\":\"" + material + "\",\"questionCount\":2}"))
                .andExpect(status().isOk()).andReturn();
        var data = json.readTree(started.getResponse().getContentAsString()).path("data");
        return new Ref(data.path("sessionId").asText(), data.at("/currentQuestion/questionId").asText());
    }

    /** requestId 传 null 时省略该参数，用来测缺参数。file 必须紧跟 multipart()：它只定义在 multipart builder 上。 */
    private MockHttpServletRequestBuilder transcription(MockHttpSession owner, Ref ref, String requestId) {
        MockHttpServletRequestBuilder request = multipart("/api/interviews/" + ref.sessionId() + "/transcriptions")
                .file(new MockMultipartFile("file", "answer.webm", "audio/webm", WAV))
                .param("questionId", ref.questionId());
        if (requestId != null) request.param("requestId", requestId);
        return request.header("X-User-Id", owner.getId());
    }

    private void stubTranscription() {
        when(normalizer.normalize(any())).thenReturn(new AudioNormalizer.NormalizedAudio(WAV, 3500));
        when(speech.transcribe(any(byte[].class), anyString()))
                .thenReturn(new SpeechClient.Transcript("我负责缓存优化", 1200));
    }

    private String sessionJson(MockHttpSession owner, Ref ref) throws Exception {
        MvcResult result = mvc.perform(get("/api/interviews/" + ref.sessionId()).header("X-User-Id", owner.getId()))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").toString();
    }

    private MvcResult answerCurrent(MockHttpSession owner, Ref ref, String answer) throws Exception {
        return mvc.perform(post("/api/interviews/" + ref.sessionId() + "/turns").header("X-User-Id", owner.getId())
                        .contentType("application/json")
                        .content("{\"questionId\":\"" + ref.questionId() + "\",\"answer\":\"" + answer + "\"}"))
                .andExpect(status().isOk()).andReturn();
    }

    // ------------------------------------------------------------------

    @Test void capabilitiesReportLocalConfigurationOnly() throws Exception {
        mvc.perform(get("/api/interview-speech/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.asrEnabled").value(true))
                .andExpect(jsonPath("$.data.ttsEnabled").value(true))
                .andExpect(jsonPath("$.data.maxRecordingSeconds").value(120))
                .andExpect(jsonPath("$.data.maxAudioBytes").value(5242880));
        verifyNoInteractions(speech, normalizer);
    }

    @Test void transcriptionReturnsDraftWithoutTouchingTheInterview() throws Exception {
        var owner = new MockHttpSession();
        Ref ref = start(owner);
        int resolverBaseline = resolverInvocations();  // 上传+出题各一次，之后不应再增加
        stubTranscription();
        String before = sessionJson(owner, ref);

        mvc.perform(transcription(owner, ref, UUID_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").value(ref.sessionId()))
                .andExpect(jsonPath("$.data.questionId").value(ref.questionId()))
                .andExpect(jsonPath("$.data.requestId").value(UUID_1))
                .andExpect(jsonPath("$.data.text").value("我负责缓存优化"))
                .andExpect(jsonPath("$.data.durationMs").value(3500));

        assertEquals(before, sessionJson(owner, ref), "转写不能改 turns / status / pendingAnswer");
        // start 之外没有再解析模型客户端，说明没有触发下一题
        verify(resolver, times(resolverBaseline)).resolve(any(HttpServletRequest.class));
        ArgumentCaptor<byte[]> wav = ArgumentCaptor.forClass(byte[].class);
        verify(speech).transcribe(wav.capture(), eq(UUID_1));
        assertArrayEquals(WAV, wav.getValue());
    }

    @Test void transcriptionGuardsOwnershipStateAndInput() throws Exception {
        var owner = new MockHttpSession();
        Ref ref = start(owner);

        // 他人浏览器：404，且消息来自面试 advice 而不是全局兜底的 500
        mvc.perform(transcription(new MockHttpSession(), ref, UUID_1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("面试不存在或已过期，请重新开始"));

        // 缺文件、缺参数、requestId 不是 UUID
        mvc.perform(multipart("/api/interviews/" + ref.sessionId() + "/transcriptions")
                        .param("questionId", ref.questionId()).param("requestId", UUID_1).header("X-User-Id", owner.getId()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(transcription(owner, ref, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(transcription(owner, ref, "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求参数不正确，请重新提交"));

        // 答完当前题之后，旧题不能再转写
        answerCurrent(owner, ref, "负责缓存优化");
        mvc.perform(transcription(owner, ref, UUID_1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("面试状态已变化，请同步当前题目后继续"));

        // 已结束的会话
        mvc.perform(post("/api/interviews/" + ref.sessionId() + "/finish").header("X-User-Id", owner.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FINISHED"));
        mvc.perform(transcription(owner, ref, UUID_1)).andExpect(status().isConflict());

        verify(speech, never()).transcribe(any(byte[].class), anyString());
    }

    @Test void pendingAnswerBlocksTranscription() throws Exception {
        var owner = new MockHttpSession();
        Ref ref = start(owner);
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn((s, p) -> {
            throw new AiClient.AiException("生成失败", false);
        });
        mvc.perform(post("/api/interviews/" + ref.sessionId() + "/turns").header("X-User-Id", owner.getId())
                        .contentType("application/json")
                        .content("{\"questionId\":\"" + ref.questionId() + "\",\"answer\":\"我的回答\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/interviews/" + ref.sessionId()).header("X-User-Id", owner.getId()))
                .andExpect(jsonPath("$.data.pendingAnswer").value("我的回答"));
        mvc.perform(transcription(owner, ref, UUID_1)).andExpect(status().isConflict());
        verify(speech, never()).transcribe(any(byte[].class), anyString());
    }

    @Test void synthesisReadsTheServerQuestionAndKeepsHistoryReplayable() throws Exception {
        var owner = new MockHttpSession();
        Ref ref = start(owner);
        when(speech.synthesize(anyString(), anyString()))
                .thenReturn(new SpeechClient.SpeechAudio(SYNTH, "audio/mpeg"));

        MvcResult result = mvc.perform(post("/api/interviews/" + ref.sessionId()
                        + "/questions/" + ref.questionId() + "/speech")
                        .header("X-User-Id", owner.getId()).contentType("application/json")
                        .content("{\"text\":\"用户想自己朗读的文字\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/mpeg"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertArrayEquals(SYNTH, result.getResponse().getContentAsByteArray());

        // 答过的历史题允许重播
        String answered = ref.questionId();
        answerCurrent(owner, ref, "负责缓存优化");
        mvc.perform(post("/api/interviews/" + ref.sessionId() + "/questions/" + answered + "/speech").header("X-User-Id", owner.getId()))
                .andExpect(status().isOk()).andExpect(content().contentType("audio/mpeg"));

        ArgumentCaptor<String> text = ArgumentCaptor.captor();
        verify(speech, times(2)).synthesize(text.capture(), anyString());
        assertTrue(text.getAllValues().stream().allMatch(QUESTION::equals),
                "朗读内容只能来自服务端题目，不能来自请求体");

        mvc.perform(post("/api/interviews/" + ref.sessionId() + "/questions/nope/speech").header("X-User-Id", owner.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("题目不存在，请刷新面试记录"));
    }

    @Test void oversizedTranscriptionUploadIsRejectedWith413() throws Exception {
        var owner = new MockHttpSession();
        Ref ref = start(owner);
        String body = "--boundary\r\n"
                + "Content-Disposition: form-data; name=\"questionId\"\r\n\r\n" + ref.questionId() + "\r\n"
                + "Content-Disposition: form-data; name=\"requestId\"\r\n\r\n" + UUID_1 + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"answer.webm\"\r\n"
                + "Content-Type: audio/webm\r\n\r\n"
                + "a".repeat(5 * 1024 * 1024 + 1) + "\r\n--boundary--\r\n";
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                + "/api/interviews/" + ref.sessionId() + "/transcriptions"))
                        .header("Content-Type", "multipart/form-data; boundary=boundary")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(413, response.statusCode());
        assertFalse(json.readTree(response.body()).path("success").asBoolean());
        verifyNoInteractions(speech);
    }

    @Test void transcriptionRechecksStateAfterTheVendorCall() throws Exception {
        var owner = new MockHttpSession();
        Ref ref = start(owner);
        int resolverBaseline = resolverInvocations();  // 上传+出题各一次，之后不应再增加
        CountDownLatch asrStarted = new CountDownLatch(1);
        CountDownLatch asrRelease = new CountDownLatch(1);
        when(normalizer.normalize(any())).thenReturn(new AudioNormalizer.NormalizedAudio(new byte[]{1}, 100));
        when(speech.transcribe(any(byte[].class), anyString()))
                .thenAnswer(invocation -> {
                    asrStarted.countDown();
                    asrRelease.await(5, TimeUnit.SECONDS);
                    return new SpeechClient.Transcript("识别结果", 100);
                });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> pending = pool.submit(
                    () -> mvc.perform(transcription(owner, ref, UUID_1)).andReturn());
            assertTrue(asrStarted.await(5, TimeUnit.SECONDS), "ASR 调用应已开始");

            // ASR 还没回来时，用户已经结束了面试
            mvc.perform(post("/api/interviews/" + ref.sessionId() + "/finish").header("X-User-Id", owner.getId()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FINISHED"));
            asrRelease.countDown();

            MvcResult result = pending.get(5, TimeUnit.SECONDS);
            assertEquals(409, result.getResponse().getStatus(), "识别期间会话已结束，结果不能当成有效草稿");
            assertFalse(json.readTree(result.getResponse().getContentAsString()).path("success").asBoolean());
            verify(speech, times(1)).transcribe(any(byte[].class), anyString());
            verify(resolver, times(resolverBaseline)).resolve(any(HttpServletRequest.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void sameOwnerSpeechRequestsAreSerialisedAndPermitsAreReleased() throws Exception {
        var owner = new MockHttpSession();
        Ref ref = start(owner);
        CountDownLatch asrStarted = new CountDownLatch(1);
        CountDownLatch asrRelease = new CountDownLatch(1);
        when(normalizer.normalize(any()))
                .thenReturn(new AudioNormalizer.NormalizedAudio(WAV, 100))
                .thenThrow(new InterviewException(415, "无法读取录音格式，请重新录音"))
                .thenReturn(new AudioNormalizer.NormalizedAudio(WAV, 100));
        when(speech.transcribe(any(byte[].class), anyString()))
                .thenAnswer(invocation -> {
                    asrStarted.countDown();
                    asrRelease.await(5, TimeUnit.SECONDS);
                    return new SpeechClient.Transcript("识别结果", 100);
                });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> first = pool.submit(
                    () -> mvc.perform(transcription(owner, ref, UUID_1)).andReturn());
            assertTrue(asrStarted.await(5, TimeUnit.SECONDS), "ASR 调用应已开始");

            // 同一浏览器的第二个语音请求必须立刻被拒，而不是排队
            mvc.perform(transcription(owner, ref, "22222222-2222-4222-8222-222222222222"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.message").value("语音服务繁忙，请稍后再试"));

            asrRelease.countDown();
            assertEquals(200, first.get(5, TimeUnit.SECONDS).getResponse().getStatus());
            // 第一次调用成功，第二次抛错，第三次起又成功：
            // 成功和异常两条路径都必须把 permit 放回来，否则后续请求会一直 429。
            mvc.perform(transcription(owner, ref, "44444444-4444-4444-8444-444444444444"))
                    .andExpect(status().isUnsupportedMediaType());
            mvc.perform(transcription(owner, ref, "55555555-5555-4555-8555-555555555555"))
                    .andExpect(status().isOk());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void rejectedGlobalRequestDoesNotIncreaseCapacity() throws Exception {
        var owners = new MockHttpSession[]{new MockHttpSession(), new MockHttpSession(),
                new MockHttpSession(), new MockHttpSession()};
        Ref[] refs = new Ref[owners.length];
        for (int i = 0; i < owners.length; i++) refs[i] = start(owners[i]);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(speech.synthesize(anyString(), anyString())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() <= 2) {
                started.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return new SpeechClient.SpeechAudio(SYNTH, "audio/mpeg");
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = pool.submit(() -> mvc.perform(post("/api/interviews/"
                    + refs[0].sessionId() + "/questions/" + refs[0].questionId() + "/speech")
                    .header("X-User-Id", owners[0].getId())).andReturn());
            Future<MvcResult> second = pool.submit(() -> mvc.perform(post("/api/interviews/"
                    + refs[1].sessionId() + "/questions/" + refs[1].questionId() + "/speech")
                    .header("X-User-Id", owners[1].getId())).andReturn());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 2; i < 4; i++) {
                mvc.perform(post("/api/interviews/" + refs[i].sessionId() + "/questions/"
                        + refs[i].questionId() + "/speech").header("X-User-Id", owners[i].getId()))
                        .andExpect(status().isTooManyRequests());
            }
            assertEquals(2, calls.get(), "被拒请求不能制造额外许可或调用厂商");
            release.countDown();
            assertEquals(200, first.get(5, TimeUnit.SECONDS).getResponse().getStatus());
            assertEquals(200, second.get(5, TimeUnit.SECONDS).getResponse().getStatus());
            mvc.perform(post("/api/interviews/" + refs[2].sessionId() + "/questions/"
                    + refs[2].questionId() + "/speech").header("X-User-Id", owners[2].getId()))
                    .andExpect(status().isOk());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
