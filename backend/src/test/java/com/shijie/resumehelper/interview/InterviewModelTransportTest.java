package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.ai.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
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

    @Test void reasoningOnlyResponseIsRejectedAndNeverBecomesQuestion() throws Exception {
        // 推理模型可能把思考写进 reasoning_content 而 content 为空。
        // 思考文本绝不能当成正式面试题返回，也不能被朗读出来。
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"\",\"reasoning_content\":\"思考过程：这道题应该问缓存穿透\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var client = new OpenAiCompatibleAiClient(new AiCredentials("test-only-key", "http://127.0.0.1:"+server.getAddress().getPort()+"/v1", "test-model"), new AiProperties(), new ObjectMapper());
            var thrown = assertThrows(AiClient.AiException.class, () -> client.chat("系统提示", "用户提示"));
            assertFalse(thrown.getMessage().contains("思考过程"), "错误提示不应回显思考内容");
            var agent = assertThrows(InterviewException.class, () -> new InterviewAgent().nextQuestion("简历内容", "Java后端", List.of(), 5, client));
            assertEquals(502, agent.status());
            assertFalse(agent.getMessage().contains("思考过程"));
        } finally { server.stop(0); }
    }

    @Test void serviceDoesNotAdvanceTurnWhenModelReturnsReasoningOnly() throws Exception {
        // 思考文本不能被提交成新一轮问题：pending 原回答保留，题数不推进。
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"\",\"reasoning_content\":\"思考过程：应该追问缓存命中率\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var ai = new OpenAiCompatibleAiClient(new AiCredentials("test-only-key", "http://127.0.0.1:"+server.getAddress().getPort()+"/v1", "test-model"), new AiProperties(), new ObjectMapper());
            // 面试服务不再自己查材料：统一材料模块后，简历正文由调用方传入
            var service = new InterviewService(new InterviewAgent());
            var session = service.start("owner", "mat-test",
                    "张同学：Java Spring Boot 校园项目，负责 Redis 缓存优化。", "", 3, (s, p) -> "介绍你的项目？");
            String questionId = session.currentQuestion().questionId();
            assertThrows(InterviewException.class, () -> service.answer("owner", session.sessionId(), questionId, "我的回答", ai));
            var state = service.get("owner", session.sessionId());
            assertEquals("ACTIVE", state.status());
            assertEquals(1, state.turns().size(), "失败后不应新增任何一轮");
            assertEquals(questionId, state.currentQuestion().questionId());
            assertEquals("我的回答", state.pendingAnswer());
            assertFalse(state.turns().stream().anyMatch(t -> t.question() != null && t.question().contains("思考过程")));
        } finally { server.stop(0); }
    }

    @Test void clientDoesNotFollowRedirectsFromModelEndpoint() throws Exception {
        // 禁止跟随重定向：否则一个受信的网关地址可以被用来把密钥送往别处。
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"重定向后不应到达\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/v1/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/v1/chat/completions");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        try {
            var client = new OpenAiCompatibleAiClient(new AiCredentials("test-only-key", "http://127.0.0.1:"+server.getAddress().getPort()+"/v1/redirect", "test-model"), new AiProperties(), new ObjectMapper());
            var thrown = assertThrows(AiClient.AiException.class, () -> client.chat("系统提示", "用户提示"));
            assertTrue(thrown.getMessage().contains("302"), "302 未被跟随而被当成异常状态返回：" + thrown.getMessage());
        } finally { server.stop(0); }
    }
}
