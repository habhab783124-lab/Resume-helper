package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.shijie.resumehelper.interview.InterviewTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-interface recovery checks using real snapshots and a controlled local model. */
class InterviewReliabilityIntegrationTest {
    @TempDir Path directory;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T08:00:00Z"), ZoneOffset.UTC);

    private InterviewStore store() {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new FileSystemInterviewStore(directory, mapper);
    }

    private InterviewService service(InterviewStore store) {
        return new InterviewService(new InterviewAgent(), store, CLOCK);
    }

    private AiClient model() {
        return (system, prompt) -> prompt.contains("selectedAlgorithm")
                ? algorithmFromPrompt(prompt) : question("请说明你如何验证缓存优化效果？");
    }

    @Test
    void finishFencesLateGenerationAndRestartsQueuedScoringAsRetryable() {
        var store = store();
        var first = service(store);
        var opened = first.start("alice", "material-1", "Java 项目与 Redis 缓存", "Java 后端", 13, model());
        var answer = new InterviewService.Submission(opened.currentQuestion().questionId(), "submission-1", "用监控验证了缓存命中率。");
        var saved = first.saveAnswer("alice", opened.sessionId(), answer);
        String generationId = first.claimGeneration("alice", opened.sessionId(), "next-1",
                InterviewSnapshot.GenerationKind.NEXT, saved.revision());

        var finished = first.finish("alice", opened.sessionId(), "finish-1", null);
        assertEquals("USER_ENDED", finished.finishReason());
        assertThrows(InterviewException.class, () -> first.commitGenerationSuccess("alice", opened.sessionId(),
                generationId, saved.revision(), new InterviewService.GenerationResult(
                        contract("CORE", "INTERMEDIATE"), InterviewService.Kind.MAIN, null, 2, null, null, null)));

        var queued = first.queueReport("alice", opened.sessionId(), "report-1", false);
        assertTrue(queued.enqueue());
        assertEquals("QUEUED", queued.report().scoringState());

        var restarted = service(store);
        var restored = restarted.get("alice", opened.sessionId());
        assertEquals("FINISHED", restored.status());
        assertEquals("USER_ENDED", restored.finishReason());
        assertEquals("用监控验证了缓存命中率。", restored.turns().get(0).answer());
        var report = restarted.reportSnapshot("alice", opened.sessionId());
        assertEquals("FAILED", report.scoringState(), "orphaned work must become retryable after restart");
        assertTrue(restarted.queueReport("alice", opened.sessionId(), "report-retry-1", true).enqueue());
        assertThrows(InterviewException.class, () -> restarted.get("bob", opened.sessionId()));
    }

    @Test
    void startRequestReplayAfterServiceRestartReturnsTheSameOpening() {
        var store = store();
        var first = service(store);
        var one = first.start("alice", "material-1", "Java 项目与 Redis 缓存", "Java 后端", 13, model());
        // This verifies replay through the durable start request index independently of in-memory state.
        var asyncOne = first.startAsync("alice", "material-2", "另一份简历", "运营", "start-request-1");
        var restarted = service(store);
        var replay = restarted.startAsync("alice", "material-2", "另一份简历", "运营", "start-request-1");
        assertEquals(asyncOne.sessionId(), replay.sessionId());
        assertNotEquals(one.sessionId(), replay.sessionId());
    }
}
