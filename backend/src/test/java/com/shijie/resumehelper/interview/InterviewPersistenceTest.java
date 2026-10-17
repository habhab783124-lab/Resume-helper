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
import java.util.concurrent.atomic.AtomicInteger;

import static com.shijie.resumehelper.interview.InterviewTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * T03：会话生命周期接入持久化。覆盖跨实例恢复、写失败不增 revision、
 * 恢复后已成功评分不再调用模型、过期/越权不返回、start 落盘 legacy-v3 计划。
 */
class InterviewPersistenceTest {

    private static final String RESUME = "张同学：Java Spring Boot 校园项目，负责 Redis 缓存优化。";
    private static final Instant NOW = Instant.parse("2026-10-17T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir Path dir;

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private InterviewService service(InterviewStore store) {
        return new InterviewService(new InterviewAgent(), store, CLOCK);
    }

    private InterviewStore store() {
        return new FileSystemInterviewStore(dir, mapper());
    }

    private AiClient echoAi() {
        return (system, prompt) -> {
            if (prompt.contains("selectedAlgorithm")) return algorithmFromPrompt(prompt);
            return question("请说明你如何验证缓存效果？");
        };
    }

    @Test
    void serviceBRestoresSessionFromSameStoreDirectory() {
        var store = store();
        var a = service(store);
        var session = a.start("alice", "mat-1", RESUME, "Java 后端", 13, echoAi());
        String q = session.currentQuestion().questionId();
        var answered = a.saveAnswer("alice", session.sessionId(),
                new InterviewService.Submission(q, "sub-1", "缓存命中率达到90%"));

        // 服务 B：新实例、空内存、同一目录
        var b = service(store);
        var restored = b.get("alice", session.sessionId());
        assertEquals(answered.sessionId(), restored.sessionId());
        assertEquals("ACTIVE", restored.status());
        assertNull(restored.currentQuestion(), "saveAnswer 后不应有当前题");
        assertEquals("缓存命中率达到90%", restored.turns().get(0).answer());
        // revision/stateVersion 已随保存递增并在恢复后保留
        assertTrue(restored.stateVersion() >= 2);
        assertEquals(answered.revision(), restored.revision());
    }

    @Test
    void failedWriteDoesNotIncrementRevisionAndKeepsOldState() {
        var realStore = store();
        var a = service(realStore);
        var session = a.start("alice", "mat-1", RESUME, "Java 后端", 13, echoAi());
        long revisionBefore = a.get("alice", session.sessionId()).revision();
        long stateVersionBefore = a.get("alice", session.sessionId()).stateVersion();

        // 注入一个总是写失败的 store，但通过服务 A 的内存已发布状态验证回滚
        var failingStore = new InterviewStore() {
            public void save(InterviewSnapshot s) { throw new InterviewException(500, "模拟写失败",
                    new InterviewFailure(InterviewFailure.Code.STORAGE, false, "会话存储失败", null)); }
            public java.util.Optional<InterviewSnapshot> find(String o, String id) { return realStore.find(o, id); }
            public java.util.Optional<InterviewSnapshot> findByStartRequestId(String o, String r) { return java.util.Optional.empty(); }
            public int deleteExpired(Instant now) { return 0; }
        };
        var failingService = service(failingStore);
        // 复制内存状态：先让 failingService 从 realStore 恢复同一会话
        failingService.get("alice", session.sessionId());
        String q = failingService.get("alice", session.sessionId()).currentQuestion().questionId();

        var error = assertThrows(InterviewException.class, () -> failingService.saveAnswer("alice", session.sessionId(),
                new InterviewService.Submission(q, "sub-fail", "失败路径的回答")));
        assertEquals(InterviewFailure.Code.STORAGE, error.failure().code());

        // revision/stateVersion 不因写失败递增；旧内存状态回滚（currentQuestion 仍是原题）
        var after = failingService.get("alice", session.sessionId());
        assertEquals(revisionBefore, after.revision());
        assertEquals(stateVersionBefore, after.stateVersion());
        assertEquals(q, after.currentQuestion().questionId());
    }

    @Test
    void restoredSessionWithCachedReportDoesNotInvokeModel() {
        var store = store();
        var a = service(store);
        var calls = new AtomicInteger();
        AiClient ai = (system, prompt) -> {
            if (prompt.contains("selectedAlgorithm")) return algorithmFromPrompt(prompt);
            calls.incrementAndGet();
            if (prompt.contains("inputs")) return assessmentsFromPrompt(prompt, 15);
            return question("请说明你的项目？");
        };
        var session = a.start("alice", "mat-1", RESUME, "Java 后端", 13, ai);
        // 答完 13 题到 FINISHED
        var end = session;
        while (end.currentQuestion() != null)
            end = a.answer("alice", end.sessionId(), end.currentQuestion().questionId(), "通过监控验证", ai);
        assertEquals("FINISHED", end.status());
        a.report("alice", end.sessionId(), ai);
        int modelCallsForFlow = calls.get();

        // 服务 B 恢复：report 应命中 cachedReport，模型调用数不再增加
        var b = service(store);
        b.get("alice", end.sessionId());
        b.report("alice", end.sessionId(), ai);
        assertEquals(modelCallsForFlow, calls.get(), "恢复后已成功评分不应再调用模型");
    }

    @Test
    void expiredOrCrossOwnerSessionIsNotFound() {
        var store = store();
        var pastClock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);
        var pastService = new InterviewService(new InterviewAgent(), store, pastClock);
        var session = pastService.start("alice", "mat-1", RESUME, "Java 后端", 13, echoAi());

        // 当前时间已超过过期时间
        var now = service(store);
        assertThrows(InterviewException.class, () -> now.get("alice", session.sessionId()));
        // 越权
        var fresh = service(store);
        var alive = fresh.start("bob", "mat-2", RESUME, "Java 后端", 13, echoAi());
        assertThrows(InterviewException.class, () -> fresh.get("alice", alive.sessionId()));
    }

    @Test
    void startPersistsLegacyV3PlanAndSchemaVersionTwo() {
        var store = store();
        var a = service(store);
        var session = a.start("alice", "mat-1", RESUME, "Java 后端", 13, echoAi());

        var snap = store.find("alice", session.sessionId()).orElseThrow();
        assertEquals(2, snap.schemaVersion());
        assertNotNull(snap.plan());
        assertEquals("legacy-v3", snap.plan().policyVersion());
        assertEquals(InterviewPlan.Mode.FULL, snap.plan().mode());
        assertEquals(InterviewService.MAIN_COUNT, snap.plan().mainCount());
        assertTrue(snap.stateVersion() >= 1);
        assertEquals(session.sessionId(), snap.sessionId());
    }

    @Test
    void inFlightGenerationIsNotRevivedAfterRestart() {
        var store = store();
        var a = service(store);
        var session = a.start("alice", "mat-1", RESUME, "Java 后端", 13, echoAi());
        a.saveAnswer("alice", session.sessionId(),
                new InterviewService.Submission(session.currentQuestion().questionId(), "sub-1", "缓存命中率达到90%"));
        long revision = a.get("alice", session.sessionId()).revision();
        // 认领一次 NEXT 生成后即"重启"：QUEUED 操作落盘，但没有存活的工作线程
        String staleId = a.claimGeneration("alice", session.sessionId(), "req-a",
                InterviewSnapshot.GenerationKind.NEXT, revision);

        var b = service(store);   // 进程重启：新实例、空内存、同一目录
        b.get("alice", session.sessionId());
        // 迟到结果不得应用到已被中断的操作
        assertThrows(InterviewException.class, () -> b.commitGenerationSuccess("alice", session.sessionId(),
                staleId, revision, nextResult()));
        // 新认领得到新操作，而不是复活的旧操作
        assertFalse(staleId.equals(b.claimGeneration("alice", session.sessionId(), "req-b",
                InterviewSnapshot.GenerationKind.NEXT, revision)), "重启后不得复活在途生成操作");
    }

    private static InterviewService.GenerationResult nextResult() {
        return new InterviewService.GenerationResult(contract("CORE", "INTERMEDIATE"),
                InterviewService.Kind.MAIN, null, 2, null, null, null);
    }
}
