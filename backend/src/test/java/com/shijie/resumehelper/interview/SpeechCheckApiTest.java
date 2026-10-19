package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.ai.AiClientResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设置页「测试连接」接口 {@code POST /api/interview-speech/check}。
 *
 * <p>mock 掉 {@link SpeechClientResolver} 与 {@link SpeechClient}：本类只验证 HTTP 层与编排层
 * ——状态码、归属校验、并发闸门、以及「检查只朗读固定短文本」。
 *
 * <p>密钥那条链路不在这类里验：真实 resolver 会 {@code new} 出真实客户端并打线上服务，
 * 所以「请求头 key 优先」由 {@link SpeechClientResolverTest} 断言，
 * 「解析出的 key 真的写进了 X-Api-Key 头」由 {@link SpeechClientTest} 断言。
 * 三段合起来覆盖了整条链。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT) @AutoConfigureMockMvc
class SpeechCheckApiTest {

    @org.springframework.test.context.DynamicPropertySource
    static void speechProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        // 服务端不配 key：能力判断与检查失败都必须来自请求头，测不出对服务端配置的隐式依赖。
        registry.add("app.interview.speech.api-key", () -> "");
        registry.add("app.interview.speech.ffmpeg-path", () -> "java");
        registry.add("app.interview.speech.ffprobe-path", () -> "java");
        registry.add("app.auth.enforce", () -> false);
    }

    private static final byte[] AUDIO = {1, 2, 3};

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AiClientResolver aiResolver;
    @MockBean AudioNormalizer normalizer;
    @MockBean SpeechClientResolver speechClients;
    @MockBean SpeechClient speech;

    @BeforeEach
    void stubSpeech() {
        when(speechClients.resolve(any(HttpServletRequest.class))).thenReturn(speech);
        when(speechClients.apiKey(any(HttpServletRequest.class))).thenReturn("");
        when(speech.synthesize(any(String.class), any(String.class)))
                .thenReturn(new SpeechClient.SpeechAudio(AUDIO, "audio/mpeg"));
    }

    @Test void headerKeyOpensTheCheckAndResultCarriesTheTranscodedAudio() throws Exception {
        when(speechClients.apiKey(any(HttpServletRequest.class))).thenReturn("user-test-key");

        var result = mvc.perform(post("/api/interview-speech/check")
                        .header("X-User-Id", "demo-user")
                        .header(SpeechClientResolver.HEADER_API_KEY, "user-test-key")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.speaker").value("zh_female_vv_uranus_bigtts"))
                .andExpect(jsonPath("$.data.audioBytes").value(AUDIO.length))
                .andReturn();
        json.readTree(result.getResponse().getContentAsString());

        ArgumentCaptor<String> text = ArgumentCaptor.captor();
        verify(speech).synthesize(text.capture(), any(String.class));
        assertEquals("语音配置测试", text.getValue(), "检查走固定的短文本，不朗读用户输入");
        // 控制器确实按请求解析了客户端，而不是走某个全局单例。
        verify(speechClients).resolve(any(HttpServletRequest.class));
    }

    @Test void vendorFailurePropagatesUnchanged() throws Exception {
        // 客户端抛的 503 原样透出成 HTTP 503，消息不变。
        // 这里用的是真实客户端抛的那个文案：mock 只是复现它，
        // 「空 key 到底抛不抛」由 SpeechClientTest.ttsSendsTheKeyResolvedForThisRequest 断言。
        when(speech.synthesize(any(String.class), any(String.class)))
                .thenThrow(new InterviewException(503, "语音服务未配置，文字面试仍可继续使用"));

        mvc.perform(post("/api/interview-speech/check")
                        .header("X-User-Id", "demo-user")
                        .header(SpeechClientResolver.HEADER_API_KEY, "user-test-key")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("语音服务未配置，文字面试仍可继续使用"));
    }

    @Test void capabilitiesFollowTheResolvedKey() throws Exception {
        // 服务端没配 key，所以解析出空 key 时语音是关的。
        mvc.perform(get("/api/interview-speech/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.asrEnabled").value(false))
                .andExpect(jsonPath("$.data.ttsEnabled").value(false));

        when(speechClients.apiKey(any(HttpServletRequest.class))).thenReturn("user-test-key");
        mvc.perform(get("/api/interview-speech/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.asrEnabled").value(true))
                .andExpect(jsonPath("$.data.ttsEnabled").value(true));
        verify(speech, times(0)).synthesize(any(String.class), any(String.class));
    }
}
