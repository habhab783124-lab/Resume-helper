package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.ai.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class InterviewModelTransportTest {
    @Test void realCompatibleClientSendsResumeHistoryAndReturnsQuestion() throws Exception {
        var payload = new AtomicReference<String>();
        var auth = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"缓存失效后如何保护数据库？\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var client = new OpenAiCompatibleAiClient(new AiCredentials("test-only-key", "http://127.0.0.1:"+server.getAddress().getPort()+"/v1", "test-model"), new AiProperties(), new ObjectMapper());
            String question = new InterviewAgent().nextQuestion("Java Redis 校园项目", "Java后端", List.of(new InterviewService.Turn("q1", "如何优化？", "增加缓存")), 5, client);
            assertEquals("缓存失效后如何保护数据库？", question);
            var body = new ObjectMapper().readTree(payload.get());
            assertEquals("test-model", body.path("model").asText());
            assertEquals("Bearer test-only-key", auth.get());
            assertTrue(body.at("/messages/1/content").asText().contains("增加缓存"));
            assertTrue(body.at("/messages/1/content").asText().contains("Redis"));
        } finally { server.stop(0); }
    }
}
