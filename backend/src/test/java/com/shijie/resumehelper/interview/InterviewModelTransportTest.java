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
    @Test void realReportUsesDedicatedOutputBudgetForFiveAssessments() throws Exception {
        var budget = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var request = new ObjectMapper().readTree(exchange.getRequestBody());
            budget.set(request.path("max_tokens").asInt());
            String content = budget.get() < 8192 ? "{\"assessments\":["
                    : InterviewTestFixtures.assessmentsFromPrompt(request.at("/messages/1/content").asText(), 80);
            byte[] body = InterviewTestFixtures.json(java.util.Map.of("choices", java.util.List.of(
                    java.util.Map.of("message", java.util.Map.of("content", content))))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var config = new AiProperties();
            var client = new OpenAiCompatibleAiClient(new AiCredentials("test-only-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model"), config, new ObjectMapper());
            var inputs = java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(i -> new InterviewAgent.AssessmentInput(
                    new InterviewService.Turn("q" + i, "问题" + i, "实际回答" + i),
                    InterviewTestFixtures.contract("CORE", "INTERMEDIATE"))).toList();
            var result = new InterviewAgent().assessAnswers("简历", "后端", inputs, List.of(), client);
            assertEquals(5, result.size(), "多题结构化评分需使用独立输出预算");
            assertEquals(8192, budget.get());
            assertEquals(800, config.getMaxTokens(), "不得修改其他业务共享的输出预算");
        } finally { server.stop(0); }
    }

    @Test void realCompatibleClientSendsResumeHistoryAndReturnsQuestion() throws Exception {
        var payload = new AtomicReference<String>();
        var auth = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = InterviewTestFixtures.json(java.util.Map.of("choices", java.util.List.of(java.util.Map.of("message", java.util.Map.of("content", InterviewTestFixtures.question("缓存失效后如何保护数据库？")))))).getBytes(StandardCharsets.UTF_8);
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
            assertEquals(2400, body.path("max_tokens").asInt());
            assertEquals("Bearer test-only-key", auth.get());
            assertTrue(body.at("/messages/0/content").asText().contains("/interview：把简历问穿"));
            assertTrue(body.at("/messages/0/content").asText().contains("当前模式：Grill"));
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
                    "张同学：Java Spring Boot 校园项目，负责 Redis 缓存优化。", "", 3, (s, p) -> InterviewTestFixtures.question("介绍你的项目？"));
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

    // --- T01：错误分类与可追踪记录 ---
    // 每类故障必须映射成可机器读取的 code，供后续重试策略和前端状态使用。
    private static HttpServer statusServer(int status, String body, String... retryAfter) throws Exception {
        var s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (retryAfter.length > 0) exchange.getResponseHeaders().add("Retry-After", retryAfter[0]);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        s.start();
        return s;
    }
    private static AiClient clientAt(HttpServer server) {
        return new OpenAiCompatibleAiClient(new AiCredentials("test-only-key",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model"),
                new AiProperties(), new ObjectMapper());
    }

    @Test void authError401MapsToAuthCode() throws Exception {
        var server = statusServer(401, "{\"error\":{\"message\":\"unauthorized\"}}");
        try {
            var thrown = assertThrows(AiClient.AiException.class, () -> clientAt(server).chat("系统", "用户"));
            assertEquals(AiClient.AiException.Code.AUTH, thrown.code());
            assertFalse(thrown.retryable());
            assertTrue(thrown.elapsedMs() >= 0);
        } finally { server.stop(0); }
    }

    @Test void notFound404MapsToConfigCode() throws Exception {
        var server = statusServer(404, "{\"error\":{\"message\":\"model not found\"}}");
        try {
            var thrown = assertThrows(AiClient.AiException.class, () -> clientAt(server).chat("系统", "用户"));
            assertEquals(AiClient.AiException.Code.CONFIG, thrown.code());
            assertFalse(thrown.retryable());
        } finally { server.stop(0); }
    }

    @Test void temporary429MapsToRateLimitedWithRetryAfter() throws Exception {
        // 没有明确额度信号时，429 视为临时限流，可重试，并尊重 Retry-After。
        var server = statusServer(429, "{\"error\":{\"message\":\"Too many requests\"}}", "5");
        try {
            var thrown = assertThrows(AiClient.AiException.class, () -> clientAt(server).chat("系统", "用户"));
            assertEquals(AiClient.AiException.Code.RATE_LIMITED, thrown.code());
            assertTrue(thrown.retryable());
            assertEquals(Long.valueOf(5000L), thrown.retryAfterMs());
        } finally { server.stop(0); }
    }

    @Test void quota429MapsToQuotaCodeAndNotRetryable() throws Exception {
        // 仅当上游明确给出额度耗尽信号时才判 QUOTA，不能只凭 429 就声称额度耗尽。
        var server = statusServer(429, "{\"error\":{\"message\":\"insufficient_quota\"}}");
        try {
            var thrown = assertThrows(AiClient.AiException.class, () -> clientAt(server).chat("系统", "用户"));
            assertEquals(AiClient.AiException.Code.QUOTA, thrown.code());
            assertFalse(thrown.retryable());
        } finally { server.stop(0); }
    }

    @Test void upstreamUnavailable503MapsToUpstreamUnavailable() throws Exception {
        var server = statusServer(503, "");
        try {
            var thrown = assertThrows(AiClient.AiException.class, () -> clientAt(server).chat("系统", "用户"));
            assertEquals(AiClient.AiException.Code.UPSTREAM_UNAVAILABLE, thrown.code());
            assertTrue(thrown.retryable());
        } finally { server.stop(0); }
    }

    @Test void readTimeoutMapsToTimeoutCode() throws Exception {
        // 客户端最低 5 秒超时；服务端故意不返回，触发读取超时。
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try { Thread.sleep(15_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var thrown = assertThrows(AiClient.AiException.class, () -> clientAt(server).chat("系统", "用户", 5));
            assertEquals(AiClient.AiException.Code.TIMEOUT, thrown.code());
            assertTrue(thrown.retryable());
            assertTrue(thrown.elapsedMs() >= 4000, "应记录本次调用的耗时：" + thrown.elapsedMs());
        } finally { server.stop(0); }
    }

    @Test void malformedJsonMapsToInvalidOutput() throws Exception {
        var server = statusServer(200, "{not-json");
        try {
            var thrown = assertThrows(AiClient.AiException.class, () -> clientAt(server).chat("系统", "用户"));
            assertEquals(AiClient.AiException.Code.INVALID_OUTPUT, thrown.code());
            assertTrue(thrown.retryable());
        } finally { server.stop(0); }
    }
}
