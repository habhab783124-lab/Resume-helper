package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.job.ai.AiClientResolver;
import com.shijie.resumehelper.job.config.JobModuleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class AiCredentialBoundaryTest {
    @Test void serverKeyCannotBeRedirectedByRequestHeader() {
        var config = new JobModuleProperties();
        config.getAi().setApiKey("server-test-key");
        config.getAi().setBaseUrl("https://configured.example/v1");
        var resolver = new AiClientResolver(config, new ObjectMapper());
        var request = new MockHttpServletRequest();
        request.addHeader("X-AI-Base-Url", "https://untrusted.example/v1");
        assertEquals("https://configured.example/v1", resolver.fromRequest(request).baseUrl());
        request.addHeader("X-AI-Api-Key", "user-test-key");
        assertEquals("https://untrusted.example/v1", resolver.fromRequest(request).baseUrl());
    }
}
