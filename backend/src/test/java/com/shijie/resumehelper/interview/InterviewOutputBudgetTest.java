package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.common.ai.AiCredentials;
import com.shijie.resumehelper.common.ai.AiProperties;
import com.shijie.resumehelper.common.ai.FakeAiClient;
import com.shijie.resumehelper.common.ai.OpenAiCompatibleAiClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InterviewOutputBudgetTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void reviewBudgetAllowsCompleteMultiQuestionJsonWithoutChangingSharedDefaults() throws Exception {
        var requests = new ArrayList<Integer>();
        var authorization = new AtomicReference<String>();
        var report = mapper.createObjectNode();
        var assessments = report.putArray("assessments");
        for (int question = 1; question <= 5; question++) {
            var assessment = assessments.addObject().put("questionId", "q" + question);
            assessment.put("evidence", "I measured cache latency before and after the change. ".repeat(30));
            assessment.put("feedback", "Explain the measurement baseline and the invalidation policy.");
        }
        String completeReport = report.toString();
        assertTrue(completeReport.length() > 800 * 4);
        var server = server();
        server.createContext("/v1/chat/completions", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody());
            int budget = request.path("max_tokens").asInt();
            requests.add(budget);
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("test-model", request.path("model").asText());
            assertEquals("review system", request.at("/messages/0/content").asText());
            // Deterministic provider stand-in: four characters per output token.
            // A low budget cuts the JSON content while leaving the HTTP envelope valid.
            String content = completeReport.substring(0, Math.min(completeReport.length(), budget * 4));
            byte[] body = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var properties = new AiProperties();
            properties.setMaxTokens(800);
            AiClient original = client(server, properties);
            AiClient review = original.withOutputBudget(8192);
            assertNotSame(original, review);
            assertEquals(original.name(), review.name());
            assertEquals(original.available(), review.available());
            assertEquals(original.isFallback(), review.isFallback());
            assertEquals(5, mapper.readTree(review.chat("review system", "answers", 60)).path("assessments").size());
            String truncated = original.chat("review system", "answers");
            assertThrows(JsonProcessingException.class, () -> mapper.readTree(truncated));
            assertEquals(5, mapper.readTree(review.chat("review system", "answers")).path("assessments").size());
            assertEquals(List.of(8192, 800, 8192), requests);
            assertEquals(800, properties.getMaxTokens());
            assertEquals("Bearer test-only-key", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test void scopedBudgetRetainsExplicitTimeoutAndRetryableFailure() throws Exception {
        var server = server();
        server.createContext("/v1/chat/completions", exchange -> {
            try { Thread.sleep(5500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var scoped = client(server, new AiProperties()).withOutputBudget(8192);
            var failure = assertThrows(AiClient.AiException.class, () -> scoped.chat("review", "answers", 1));
            assertTrue(failure.retryable());
            assertTrue(failure.getMessage().contains("5 秒"), failure.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test void defaultBudgetMethodKeepsLambdaAndFallbackClientsCompatible() throws Exception {
        AiClient lambda = (system, user) -> system + user;
        assertSame(lambda, lambda.withOutputBudget(2400));
        assertEquals("systemuser", lambda.withOutputBudget(8192).chat("system", "user", 60));
        AiClient fallback = new FakeAiClient("offline-model");
        assertSame(fallback, fallback.withOutputBudget(8192));
        assertTrue(fallback.withOutputBudget(8192).isFallback());
        assertEquals("offline-model", fallback.withOutputBudget(8192).name());
    }

    @Test void invalidBudgetsAreRejectedBeforeChangingAnyClientOrConfiguration() throws Exception {
        var properties = new AiProperties();
        AiClient real = new OpenAiCompatibleAiClient(new AiCredentials("test-only-key", "http://127.0.0.1:1/v1", "test-model"), properties, mapper);
        AiClient lambda = (system, user) -> "ok";
        for (int budget : new int[] {Integer.MIN_VALUE, -1, 0, 32769, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> real.withOutputBudget(budget));
            assertThrows(IllegalArgumentException.class, () -> lambda.withOutputBudget(budget));
        }
        assertNotNull(real.withOutputBudget(1));
        assertNotNull(real.withOutputBudget(32768));
        assertEquals(800, properties.getMaxTokens());
    }

    private HttpServer server() throws Exception {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private AiClient client(HttpServer server, AiProperties properties) {
        return new OpenAiCompatibleAiClient(new AiCredentials("test-only-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model"), properties, mapper);
    }

}
