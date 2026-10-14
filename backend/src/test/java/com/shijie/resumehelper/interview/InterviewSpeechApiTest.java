package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.job.ai.AiClient;
import com.shijie.resumehelper.job.ai.AiClientResolver;
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
 * 真实转码由 {@link AudioNormalizerTest} 用真工具验证，真实协议由 {@link SpeechClientTest} 验证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT) @AutoConfigureMockMvc
@TestPropertySource(properties = {
        // 把 key 和工具路径钉死，capabilities 的断言不能依赖部署机恰好装了 FFmpeg。
        // 本类里 AudioNormalizer 是 mock，/usr/bin/env 只是一个"存在且可执行"的占位。
        "app.interview.speech.api-key=speech-test-key",
        "app.interview.speech.ffmpeg-path=/usr/bin/env",
        "app.interview.speech.ffprobe-path=/usr/bin/env",
        "app.interview.speech.max-concurrent-requests=2"
})
class InterviewSpeechApiTest {
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

    private void stubModel() {
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn((s, p) -> QUESTION);
    }

    private Ref start(MockHttpSession owner) throws Exception {
        stubModel();
        var upload = mvc.perform(multipart("/api/materials")
                        .file(new MockMultipartFile("file", "cv.txt", "text/plain",
                                "Java Redis 项目经验".getBytes(StandardCharsets.UTF_8)))
                        .session(owner))
                .andExpect(status().isOk()).andReturn();
        String material = json.readTree(upload.getResponse().getContentAsString()).at("/data/materialId").asText();
        MvcResult started = mvc.perform(post("/api/interviews").session(owner).contentType("application/json")
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
        return request.session(owner);
    }

    private void stubTranscription() {
        when(normalizer.normalize(any())).thenReturn(new AudioNormalizer.NormalizedAudio(WAV, 3500));
        when(speech.transcribe(any(byte[].class), anyString()))
                .thenReturn(new SpeechClient.Transcript("我负责缓存优化", 1200));
    }

    private String sessionJson(MockHttpSession owner, Ref ref) throws Exception {
        MvcResult result = mvc.perform(get("/api/interviews/" + ref.sessionId()).session(owner))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").toString();
    }

    private MvcResult answerCurrent(MockHttpSession owner, Ref ref, String answer) throws Exception {
        return mvc.perform(post("/api/interviews/" + ref.sessionId() + "/turns").session(owner)
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
        verify(resolver, times(1)).resolve(any(HttpServletRequest.class));
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
                        .param("questionId", ref.questionId()).param("requestId", UUID_1).session(owner))
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
        mvc.perform(post("/api/interviews/" + ref.sessionId() + "/finish").session(owner))
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
        mvc.perform(post("/api/interviews/" + ref.sessionId() + "/turns").session(owner)
                        .contentType("application/json")
                        .content("{\"questionId\":\"" + ref.questionId() + "\",\"answer\":\"我的回答\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/interviews/" + ref.sessionId()).session(owner))
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
                        .session(owner).contentType("application/json")
                        .content("{\"text\":\"用户想自己朗读的文字\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/mpeg"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertArrayEquals(SYNTH, result.getResponse().getContentAsByteArray());

        // 答过的历史题允许重播
        String answered = ref.questionId();
        answerCurrent(owner, ref, "负责缓存优化");
        mvc.perform(post("/api/interviews/" + ref.sessionId() + "/questions/" + answered + "/speech").session(owner))
                .andExpect(status().isOk()).andExpect(content().contentType("audio/mpeg"));

        ArgumentCaptor<String> text = ArgumentCaptor.captor();
        verify(speech, times(2)).synthesize(text.capture(), anyString());
        assertTrue(text.getAllValues().stream().allMatch(QUESTION::equals),
                "朗读内容只能来自服务端题目，不能来自请求体");

        mvc.perform(post("/api/interviews/" + ref.sessionId() + "/questions/nope/speech").session(owner))
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
            mvc.perform(post("/api/interviews/" + ref.sessionId() + "/finish").session(owner))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FINISHED"));
            asrRelease.countDown();

            MvcResult result = pending.get(5, TimeUnit.SECONDS);
            assertEquals(409, result.getResponse().getStatus(), "识别期间会话已结束，结果不能当成有效草稿");
            assertFalse(json.readTree(result.getResponse().getContentAsString()).path("success").asBoolean());
            verify(speech, times(1)).transcribe(any(byte[].class), anyString());
            verify(resolver, times(1)).resolve(any(HttpServletRequest.class));
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
}
