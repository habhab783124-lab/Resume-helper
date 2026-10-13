package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.job.ai.AiClientResolver;
import com.shijie.resumehelper.job.config.JobModuleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AiCredentialBoundaryTest {
    private JobModuleProperties serverConfig() {
        var config = new JobModuleProperties();
        config.getAi().setApiKey("server-test-key");
        config.getAi().setBaseUrl("https://configured.example/v1");
        return config;
    }

    private MockHttpServletRequest request(String baseUrl) {
        var request = new MockHttpServletRequest();
        request.addHeader("X-AI-Api-Key", "user-test-key");
        if (baseUrl != null) request.addHeader("X-AI-Base-Url", baseUrl);
        return request;
    }

    @Test void serverKeyCannotBeRedirectedByRequestHeader() {
        var resolver = new AiClientResolver(serverConfig(), new ObjectMapper());
        var request = new MockHttpServletRequest();
        request.addHeader("X-AI-Base-Url", "https://untrusted.example/v1");
        assertEquals("https://configured.example/v1", resolver.fromRequest(request).baseUrl());
    }

    @Test void browserKeyWithoutOwnBaseUrlKeepsServerConfig() {
        var resolver = new AiClientResolver(serverConfig(), new ObjectMapper());
        assertEquals("https://configured.example/v1", resolver.fromRequest(request(null)).baseUrl());
    }

    @Test void unregisteredBrowserOriginIsRejected() {
        var resolver = new AiClientResolver(serverConfig(), new ObjectMapper());
        assertThrows(IllegalArgumentException.class, () -> resolver.fromRequest(request("https://untrusted.example/v1")));
    }

    @Test void registeredBrowserOriginIsAllowed() {
        var config = serverConfig();
        config.getAi().setAllowedBrowserOrigins(List.of("https://trusted.example"));
        var resolver = new AiClientResolver(config, new ObjectMapper());
        assertEquals("https://trusted.example/v1", resolver.fromRequest(request("https://trusted.example/v1")).baseUrl());
    }

    @Test void rejectsUnsafeOrUnregisteredDestinations() {
        var config = serverConfig();
        // 部署者登记的网关；其它一切非登记目的地址都要拒绝。
        config.getAi().setAllowedBrowserOrigins(List.of("https://trusted.example", "https://gated.example:8443"));
        var resolver = new AiClientResolver(config, new ObjectMapper());
        for (String url : List.of(
                "http://trusted.example/v1",                    // 非 HTTPS
                "http://127.0.0.1:8080/v1",                     // 环回地址
                "http://localhost:5173/v1",                     // 本机前端
                "https://127.0.0.1/v1",                         // 未登记的环回
                "https://10.0.0.8/v1",                          // 内网地址
                "https://trusted.example:9443/v1",              // 非登记端口
                "https://trusted.example.evil.example/v1",      // 相似域名
                "https://user:secret@trusted.example/v1",       // userinfo
                "https://trusted.example/v1?proxy=http://x",    // 查询串
                "https://trusted.example/v1#fragment",          // 片段
                "httpx://trusted.example/v1",                   // 伪协议
                "not a url")) {
            var rejected = assertThrows(IllegalArgumentException.class, () -> resolver.fromRequest(request(url)),
                    "应拒绝：" + url);
            assertFalse(rejected.getMessage().contains(url), "错误提示不应回显用户输入的地址");
        }
    }

    @Test void nonDefaultPortRequiresExplicitRegistration() {
        var config = serverConfig();
        config.getAi().setAllowedBrowserOrigins(List.of("https://gated.example:8443"));
        var resolver = new AiClientResolver(config, new ObjectMapper());
        assertEquals("https://gated.example:8443/v1", resolver.fromRequest(request("https://gated.example:8443/v1")).baseUrl());
        assertThrows(IllegalArgumentException.class, () -> resolver.fromRequest(request("https://gated.example/v1")));
    }
}
