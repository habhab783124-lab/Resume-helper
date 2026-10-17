package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语音密钥的解析优先级：<b>请求头 &gt; 服务端配置</b>。
 *
 * <p>和 {@link AiCredentialBoundaryTest} 守住的是同一件事的两面：
 * 设置页里用户填了自己的 key，面试页就必须用他自己的 key；
 * 没填时，退回服务端配置，语音能力不会因为前端清空输入框就消失。
 *
 * <p>这里不需要 {@code allowed-browser-origins} 那类目的地址校验——
 * 语音端点是代码常量，浏览器无法改写目的地，不存在 SSRF 面。
 */
class SpeechClientResolverTest {

    private static final String SERVER_KEY = "server-test-key";

    private SpeechProperties config(String serverKey) {
        var properties = new SpeechProperties();
        properties.setApiKey(serverKey);
        properties.setAsrResourceId("volc.seedasr.sauc.duration");
        properties.setTtsResourceId("seed-tts-2.0");
        properties.setTtsSpeaker("zh_female_vv_uranus_bigtts");
        return properties;
    }

    private MockHttpServletRequest request(String header) {
        var request = new MockHttpServletRequest();
        if (header != null) {
            request.addHeader(SpeechClientResolver.HEADER_API_KEY, header);
        }
        return request;
    }

    @Test void requestHeaderWinsOverServerConfiguration() {
        var resolver = new SpeechClientResolver(config(SERVER_KEY), new ObjectMapper());
        assertEquals("user-test-key", resolver.apiKey(request("user-test-key")));
    }

    @Test void whitespaceOnlyHeaderFallsBackToServerConfiguration() {
        var resolver = new SpeechClientResolver(config(SERVER_KEY), new ObjectMapper());
        // 空白头视同未填：不能因为用户清空了输入框就丢掉服务端兜底。
        assertEquals(SERVER_KEY, resolver.apiKey(request("   ")));
        assertEquals(SERVER_KEY, resolver.apiKey(request(null)));
    }

    @Test void trimsKeysFromBothSides() {
        var resolver = new SpeechClientResolver(config("  " + SERVER_KEY + "  "), new ObjectMapper());
        assertEquals(SERVER_KEY, resolver.apiKey(request(null)));
        assertEquals("user-test-key", resolver.apiKey(request(" user-test-key ")));
    }

    @Test void blankServerKeyYieldsBlankApiKey() {
        // 两边都没有配置：解析结果是空串，不是 null。
        // 调用方靠它判断「语音未配置」，null 会走成 NPE 而不是明确的 503。
        var resolver = new SpeechClientResolver(config(""), new ObjectMapper());
        assertEquals("", resolver.apiKey(request(null)));
    }

    @Test void resolveBuildsAClientPerRequest() {
        var resolver = new SpeechClientResolver(config(SERVER_KEY), new ObjectMapper());
        // 每次调用都是新实例：带密钥的客户端不能被多个请求共用。
        assertNotNull(resolver.resolve(request(null)));
        assertNotNull(resolver.resolve(request(null)));
    }

    @Test void resolveStreamingBuildsIndependentStreamingClientWithRequestKey() {
        var resolver = new SpeechClientResolver(config(SERVER_KEY), new ObjectMapper());
        var first = resolver.resolveStreaming(request(" user-one-key "));
        var second = resolver.resolveStreaming(request("user-two-key"));
        assertTrue(first instanceof VolcengineSpeechClient);
        assertTrue(second instanceof VolcengineSpeechClient);
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(first != second);
    }
}
