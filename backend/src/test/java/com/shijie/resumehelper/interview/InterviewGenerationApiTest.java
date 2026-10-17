package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.common.ai.AiClientResolver;
import com.shijie.resumehelper.common.util.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.shijie.resumehelper.interview.InterviewTestFixtures.question;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.material.storage-dir=./target/test-generation-materials",
        "app.interview.storage-dir=./target/test-generation-sessions"
})
@AutoConfigureMockMvc
class InterviewGenerationApiTest {
    @Autowired MockMvc mvc;
    @Autowired InterviewService interviews;
    @MockBean AiClientResolver resolver;

    @Test
    void pollingIsNonBlockingAndRepeatedGenerationRequestDoesNotCallModelTwice() throws Exception {
        String owner = "generation-" + java.util.UUID.randomUUID();
        AiClient opening = (system, prompt) -> question("请介绍你负责的项目？");
        var session = interviews.start(owner, "material", "Java 后端项目经验", "Java 后端", 13, opening);
        interviews.saveAnswer(owner, session.sessionId(), new InterviewService.Submission(
                session.currentQuestion().questionId(), "submission", "负责缓存设计和监控"));
        long revision = interviews.get(owner, session.sessionId()).revision();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        AiClient blocked = (system, prompt) -> {
            calls.incrementAndGet();
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            return question("你如何验证缓存效果？");
        };
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn(blocked);
        String path = "/api/interviews/" + session.sessionId();
        try {
            mvc.perform(post(path + "/generations").header(UserContext.HEADER, owner)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"g-1\",\"kind\":\"NEXT\",\"expectedRevision\":" + revision + "}"))
                    .andExpect(status().isOk());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            mvc.perform(get(path).header(UserContext.HEADER, owner))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.generation.state").value("RUNNING"));
            mvc.perform(post(path + "/generations").header(UserContext.HEADER, owner)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"g-2\",\"kind\":\"NEXT\",\"expectedRevision\":" + revision + "}"))
                    .andExpect(status().isOk());
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void legacyTurnRetriesJoinOneGenerationAndCompletedReplayDoesNotGenerateAgain() throws Exception {
        String owner = "legacy-turn-" + java.util.UUID.randomUUID();
        AiClient opening = (system, prompt) -> question("请介绍你负责的项目？");
        var session = interviews.start(owner, "material", "Java 后端项目经验", "Java 后端", 13, opening);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        AiClient blocked = (system, prompt) -> {
            calls.incrementAndGet();
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            return question("如何验证项目效果？");
        };
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn(blocked);
        String path = "/api/interviews/" + session.sessionId() + "/turns";
        String body = "{\"questionId\":\"" + session.currentQuestion().questionId()
                + "\",\"answer\":\"负责 Redis 缓存改造\"}";
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            var first = CompletableFuture.supplyAsync(() -> postTurn(path, owner, body), callers);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var replay = CompletableFuture.supplyAsync(() -> postTurn(path, owner, body), callers);
            release.countDown();
            assertEquals(200, first.get(5, TimeUnit.SECONDS));
            assertEquals(200, replay.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());

            mvc.perform(post(path).header(UserContext.HEADER, owner)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.turns.length()").value(2));
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void legacyTurnReplayJoinsGenerationStartedThroughSeparatedApi() throws Exception {
        String owner = "mixed-protocol-" + java.util.UUID.randomUUID();
        AiClient opening = (system, prompt) -> question("请介绍你负责的项目？");
        var session = interviews.start(owner, "material", "Java 后端项目经验", "Java 后端", 13, opening);
        String questionId = session.currentQuestion().questionId();
        String answer = "负责 Redis 缓存改造";
        var waiting = interviews.saveAnswer(owner, session.sessionId(),
                new InterviewService.Submission(questionId, "new-protocol-submission", answer));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        AiClient blocked = (system, prompt) -> {
            calls.incrementAndGet();
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            return question("如何验证项目效果？");
        };
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn(blocked);
        String path = "/api/interviews/" + session.sessionId();
        try {
            mvc.perform(post(path + "/generations").header(UserContext.HEADER, owner)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"new-gen\",\"kind\":\"NEXT\",\"expectedRevision\":" + waiting.revision() + "}"))
                    .andExpect(status().isOk());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                var legacyReplay = CompletableFuture.supplyAsync(() -> postTurn(path + "/turns", owner,
                        "{\"questionId\":\"" + questionId + "\",\"answer\":\"" + answer + "\"}"), caller);
                release.countDown();
                assertEquals(200, legacyReplay.get(5, TimeUnit.SECONDS));
            } finally { caller.shutdownNow(); }
            assertEquals(1, calls.get());
            assertEquals(2, interviews.get(owner, session.sessionId()).turns().size());
        } finally { release.countDown(); }
    }

    private int postTurn(String path, String owner, String body) {
        try {
            return mvc.perform(post(path).header(UserContext.HEADER, owner)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
