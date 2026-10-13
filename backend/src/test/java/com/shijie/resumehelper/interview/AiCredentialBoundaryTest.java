package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.ai.AiClientResolver;
import com.shijie.resumehelper.common.ai.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 守住一条安全属性：<b>服务端的密钥不能被调用方改道</b>。
 *
 * <p>调用方可以用 {@code X-AI-Base-Url} 指定自己的端点——但那必须是
 * <b>用他自己 key</b> 的时候。如果服务端配了兜底密钥，而又允许请求头覆盖 baseUrl，
 * 攻击者就能让服务端拿着自己的密钥去请求攻击者的服务器，直接把密钥送出去。
 *
 * <p>统一的 AI 配置从 {@code JobModuleProperties.Ai} 移到了
 * {@code common.ai.AiProperties}，这个测试跟着改，但断言一字未动。
 */
class AiCredentialBoundaryTest {
    @Test void serverKeyCannotBeRedirectedByRequestHeader() {
        var config = new AiProperties();
        config.setApiKey("server-test-key");
        config.setBaseUrl("https://configured.example/v1");
        var resolver = new AiClientResolver(config, new ObjectMapper());
        var request = new MockHttpServletRequest();
        request.addHeader("X-AI-Base-Url", "https://untrusted.example/v1");
        assertEquals("https://configured.example/v1", resolver.fromRequest(request).baseUrl());
        request.addHeader("X-AI-Api-Key", "user-test-key");
        assertEquals("https://untrusted.example/v1", resolver.fromRequest(request).baseUrl());
    }
}
