package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.file.Files;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.shijie.resumehelper.interview.InterviewTestFixtures.question;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.material.storage-dir=./target/test-opening-materials",
        "app.interview.storage-dir=./target/test-opening-sessions"
})
@AutoConfigureMockMvc
class InterviewOpeningApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired InterviewService interviews;
    @Autowired InterviewStore store;
    @Autowired InterviewAgent agent;
    @Autowired Clock clock;
    @MockBean AiClientResolver resolver;

    @Test
    void asyncHttpStartReturnsBeforeOpeningAndReplaysRequestWithoutDuplicatingModelCall() throws Exception {
        String owner = "opening-" + java.util.UUID.randomUUID();
        var materialResponse = mvc.perform(post("/api/materials/text").header(UserContext.HEADER, owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"resume\",\"text\":\"Java Redis project experience\"}"))
                .andExpect(status().isOk()).andReturn();
        String materialId = json.readTree(materialResponse.getResponse().getContentAsString())
                .at("/data/material/id").asText();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        AiClient blocked = (system, prompt) -> {
            calls.incrementAndGet();
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            return question("请介绍一个你负责的项目？");
        };
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn(blocked);
        String body = "{\"materialId\":\"" + materialId
                + "\",\"targetRole\":\"Java backend\",\"requestId\":\"open-1\",\"async\":true}";
        try {
            var firstResponse = mvc.perform(post("/api/interviews").header(UserContext.HEADER, owner)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.currentQuestion").doesNotExist())
                    .andReturn();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            String sessionId = json.readTree(firstResponse.getResponse().getContentAsString())
                    .at("/data/sessionId").asText();
            mvc.perform(post("/api/interviews").header(UserContext.HEADER, owner)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sessionId").value(sessionId));
            mvc.perform(post("/api/interviews").header(UserContext.HEADER, owner)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body.replace("Java backend", "Product manager")))
                    .andExpect(status().isConflict());

            var restarted = new InterviewService(agent, store, clock).startAsync(owner, materialId,
                    "Java Redis project experience", "Java backend", "open-1");
            assertEquals(sessionId, restarted.sessionId());
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void failedOpeningCanRetryOnSameSessionWithoutLeavingPartialPlan() throws Exception {
        String owner = "opening-retry-" + java.util.UUID.randomUUID();
        String materialId = createMaterial(owner);
        var firstAttempt = new CountDownLatch(1);
        var calls = new AtomicInteger();
        AiClient ai = (system, prompt) -> {
            if (calls.incrementAndGet() == 1) {
                firstAttempt.countDown();
                throw new com.shijie.resumehelper.common.ai.AiClient.AiException("temporary failure");
            }
            return question("请描述你负责的服务端项目？");
        };
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn(ai);
        String body = "{\"materialId\":\"" + materialId
                + "\",\"targetRole\":\"Java backend\",\"requestId\":\"retry-open\",\"async\":true}";
        var response = mvc.perform(post("/api/interviews").header(UserContext.HEADER, owner)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertTrue(firstAttempt.await(5, TimeUnit.SECONDS));
        String sessionId = json.readTree(response.getResponse().getContentAsString()).at("/data/sessionId").asText();
        awaitGeneration(owner, sessionId, InterviewSnapshot.GenerationState.FAILED);
        var failed = interviews.get(owner, sessionId);
        assertTrue(failed.turns().isEmpty());
        assertNull(failed.specialty());

        mvc.perform(post("/api/interviews/" + sessionId + "/generations").header(UserContext.HEADER, owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"retry-open-2\",\"kind\":\"OPENING\",\"expectedRevision\":0}"))
                .andExpect(status().isOk());
        awaitGeneration(owner, sessionId, InterviewSnapshot.GenerationState.SUCCEEDED);
        var succeeded = interviews.get(owner, sessionId);
        assertEquals(1, succeeded.turns().size());
        assertNotNull(succeeded.specialty());
        assertEquals(2, calls.get());
    }

    @Test
    void canEndOpeningBeforeFirstAnswerAndLateModelResultCannotAppendQuestion() throws Exception {
        String owner = "opening-finish-" + java.util.UUID.randomUUID();
        String materialId = createMaterial(owner);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AiClient blocked = (system, prompt) -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            return question("请介绍一个项目？");
        };
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn(blocked);
        var response = mvc.perform(post("/api/interviews").header(UserContext.HEADER, owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materialId\":\"" + materialId
                                + "\",\"targetRole\":\"Java backend\",\"requestId\":\"finish-open\",\"async\":true}"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        String sessionId = json.readTree(response.getResponse().getContentAsString()).at("/data/sessionId").asText();
        mvc.perform(post("/api/interviews/" + sessionId + "/finish").header(UserContext.HEADER, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FINISHED"))
                .andExpect(jsonPath("$.data.turns").isEmpty());
        release.countDown();
        assertTrue(interviews.get(owner, sessionId).turns().isEmpty());
    }

    private String createMaterial(String owner) throws Exception {
        var response = mvc.perform(post("/api/materials/text").header(UserContext.HEADER, owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"resume\",\"text\":\"Java Redis project experience\"}"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).at("/data/material/id").asText();
    }

    private void awaitGeneration(String owner, String sessionId,
                                 InterviewSnapshot.GenerationState expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var generation = interviews.get(owner, sessionId).generation();
            if (generation != null && generation.state() == expected) return;
            Thread.onSpinWait();
        }
        fail("生成状态没有到达 " + expected);
    }

    @Test
    void asyncStartIsIdempotentAcrossServiceRestartAndRejectsChangedRole() throws Exception {
        var directory = Files.createTempDirectory("opening-start-test");
        var store = new FileSystemInterviewStore(directory, new ObjectMapper().findAndRegisterModules());
        var firstService = new InterviewService(new InterviewAgent(), store, Clock.systemUTC());

        var first = firstService.startAsync("alice", "mat-1", "Java project experience", "Java backend", "start-1");

        assertNull(first.currentQuestion());
        assertEquals("QUEUED", first.generation().state().name());
        var restartedService = new InterviewService(new InterviewAgent(), store, Clock.systemUTC());
        var replay = restartedService.startAsync("alice", "mat-1", "Java project experience", "Java backend", "start-1");
        assertEquals(first.sessionId(), replay.sessionId());
        assertThrows(InterviewException.class, () -> restartedService.startAsync(
                "alice", "mat-1", "Java project experience", "Java engineer", "start-1"));
    }
}
