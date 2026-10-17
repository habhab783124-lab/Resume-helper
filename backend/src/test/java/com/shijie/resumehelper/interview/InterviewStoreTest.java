package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T02：InterviewStore 以 schemaVersion=2 的原子文件读写一份完整面试快照。
 * 覆盖问答/契约/评分往返、跨用户隔离、非法路径、故障写入保护旧文件与到期清理。
 */
class InterviewStoreTest {

    @TempDir
    Path dir;

    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private FileSystemInterviewStore store() {
        return new FileSystemInterviewStore(dir, mapper());
    }

    private static final Instant NOW = Instant.parse("2026-10-17T08:00:00Z");

    private static InterviewSnapshot sample(String owner, String sessionId, String startRequestId,
                                            Instant expiresAt) {
        var contract = InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
        var turns = List.of(
                new InterviewSnapshot.SnapshotTurn("q-1", "介绍一次你优化缓存的经历", "通过本地缓存降低读取延迟",
                        InterviewSnapshot.AnswerStatus.ANSWERED, "sub-1"),
                new InterviewSnapshot.SnapshotTurn("q-2", "缓存击穿时怎么处理", null,
                        InterviewSnapshot.AnswerStatus.UNANSWERED, null));
        var contracts = new LinkedHashMap<String, InterviewQuestion>();
        contracts.put("q-1", contract);
        contracts.put("q-2", contract);
        var contexts = new LinkedHashMap<String, InterviewSnapshot.QuestionContext>();
        contexts.put("q-1", new InterviewSnapshot.QuestionContext(InterviewService.Kind.MAIN, null, 1, null));
        contexts.put("q-2", new InterviewSnapshot.QuestionContext(InterviewService.Kind.FOLLOW_UP, "q-1", 1, null));
        var report = new InterviewAgent.InterviewReport(new BigDecimal("15.0"), "整体总结",
                List.of("表达清晰"), List.of("缺少量化"), List.of("补充真实数据"), List.of(),
                "test-model", "PARTIAL", InterviewScoringPolicy.VERSION, 13, 1, 1);
        var generation = new InterviewSnapshot.Generation("gen-1", "gen-req-1",
                InterviewSnapshot.GenerationKind.NEXT, InterviewSnapshot.GenerationStage.NEXT_DECISION,
                InterviewSnapshot.GenerationState.RETRY_WAIT, 2, 1, 5,
                NOW, NOW.plusSeconds(120), NOW.plusSeconds(2),
                new InterviewFailure(InterviewFailure.Code.NETWORK, true, "无法连接模型服务，请检查网络或地址", null));
        var plan = InterviewPlan.legacyV3(
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "mat-1"), "后端工程师");
        return new InterviewSnapshot(InterviewSnapshot.SCHEMA_VERSION, sessionId, owner, "mat-1",
                "简历文本：三年后端经验", "后端工程师", 13, "ACTIVE", null, null,
                3, 7, turns, contracts, contexts, null, null,
                Map.of("q-1", InterviewTestFixtures.assessed("q-1", 15)), report, generation,
                startRequestId, plan, new InterviewSnapshot.Progress(13, 1, 1, 0, 0, null),
                Set.of(), NOW.minusSeconds(3600), expiresAt);
    }

    @Test
    void roundTripPreservesTurnsContractsAssessmentsAndPlan() {
        var snapshot = sample("alice", "s-1", "start-1", NOW.plusSeconds(86400));
        store().save(snapshot);

        var restored = store().find("alice", "s-1");
        assertTrue(restored.isPresent());
        assertEquals(snapshot, restored.get());
        // 题目顺序即文档顺序，重放后不能乱
        assertEquals(List.of("q-1", "q-2"),
                restored.get().turns().stream().map(InterviewSnapshot.SnapshotTurn::questionId).toList());
        assertEquals(List.of("q-1", "q-2"), List.copyOf(restored.get().questionContracts().keySet()));
    }

    @Test
    void snapshotSurvivesStoreRecreateAndNeverSerializesCredentials() throws IOException {
        var snapshot = sample("alice", "s-1", "start-1", NOW.plusSeconds(86400));
        store().save(snapshot);

        // 模拟服务重启：新建 store 实例读同一目录
        var reopened = new FileSystemInterviewStore(dir, mapper());
        assertEquals(snapshot, reopened.find("alice", "s-1").orElseThrow());

        String raw = Files.readString(dir.resolve("alice").resolve("s-1.json"));
        assertFalse(raw.contains("apiKey"), "快照不得包含凭据字段");
        assertFalse(raw.contains("Future"), "快照不得包含运行时句柄");
    }

    @Test
    void findRejectsCrossOwnerAccessEvenWithCopiedFile() throws IOException {
        store().save(sample("alice", "s-1", "start-1", NOW.plusSeconds(86400)));

        assertTrue(store().find("bob", "s-1").isEmpty());
        assertTrue(store().findByStartRequestId("bob", "start-1").isEmpty());

        // 防御性校验：文件被复制到 bob 目录，但内容 owner 是 alice，仍不可读
        Path bobDir = dir.resolve("bob");
        Files.createDirectories(bobDir);
        Files.copy(dir.resolve("alice").resolve("s-1.json"), bobDir.resolve("s-1.json"));
        assertTrue(store().find("bob", "s-1").isEmpty());
        assertTrue(store().find("alice", "s-1").isPresent());
    }

    @Test
    void deletionTombstoneSurvivesRestartAndPreventsLateSaveFromResurrectingSession() {
        var snapshot = sample("alice", "s-delete", "start-delete", NOW.plusSeconds(86400));
        var store = store();
        store.save(snapshot);
        store.delete("alice", "s-delete");

        var reopened = new FileSystemInterviewStore(dir, mapper());
        assertTrue(reopened.isDeleted("alice", "s-delete"));
        assertTrue(reopened.find("alice", "s-delete").isEmpty());
        assertTrue(reopened.findByStartRequestId("alice", "start-delete").isEmpty());
        assertDoesNotThrow(() -> reopened.delete("alice", "s-delete"));
        assertThrows(InterviewException.class, () -> reopened.save(snapshot));
    }

    @Test
    void historyPagesUseStableCreatedAtAndIdOrderingWithoutRepeatsAcrossOwners() {
        var store = store();
        store.save(sample("alice", "same-a", "req-a", NOW.plusSeconds(600)));
        store.save(sample("alice", "same-b", "req-b", NOW.plusSeconds(600)));
        store.save(sample("alice", "same-c", "req-c", NOW.plusSeconds(600)));
        store.save(sample("bob", "other", "req-o", NOW.plusSeconds(600)));

        var first = store.list("alice", 2, null, NOW);
        var second = store.list("alice", 2, first.nextCursor(), NOW);
        assertEquals(List.of("same-c", "same-b"), first.items().stream().map(InterviewStore.HistoryItem::id).toList());
        assertEquals(List.of("same-a"), second.items().stream().map(InterviewStore.HistoryItem::id).toList());
        assertNull(second.nextCursor());
        assertFalse(first.items().toString().contains("简历文本"));
    }

    @Test
    void boundedExpiryCleanupMaintainsHistoryIndex() {
        var store = store();
        store.save(sample("cleanup", "old-a", "req-old-a", NOW.minusSeconds(1)));
        store.save(sample("cleanup", "old-b", "req-old-b", NOW.minusSeconds(1)));
        assertTrue(store.list("cleanup", 10, null, NOW).items().isEmpty());
        assertEquals(1, store.deleteExpired(NOW, 1));
        assertEquals(1, (int) List.of("old-a", "old-b").stream()
                .filter(id -> store.find("cleanup", id).isPresent()).count());
        assertEquals(1, store.deleteExpired(NOW, 1));
        assertTrue(store.list("cleanup", 10, null, NOW).items().isEmpty());
        assertTrue(store.find("cleanup", "old-a").isEmpty());
        assertTrue(store.find("cleanup", "old-b").isEmpty());
    }

    @Test
    void historyIndexInitializationSkipsCorruptLegacySnapshots() throws IOException {
        Path ownerDir = dir.resolve("legacy");
        Files.createDirectories(ownerDir);
        Files.writeString(ownerDir.resolve("corrupt.json"), "{not valid json");
        assertTrue(store().list("legacy", 20, null, NOW).items().isEmpty());
        assertTrue(Files.isRegularFile(ownerDir.resolve("history-index.json")));
    }

    @Test
    void countsExcludeExpiredAndDeletedSessionsAndRemainOwnerScoped() {
        var store = store();
        store.save(sample("alice", "live", "start-live", NOW.plusSeconds(86400)));
        store.save(sample("alice", "expired", "start-expired", NOW.minusSeconds(1)));
        store.save(sample("alice", "deleted", "start-deleted", NOW.plusSeconds(86400)));
        store.save(sample("bob", "other-owner", "start-other", NOW.plusSeconds(86400)));
        store.delete("alice", "deleted");

        assertEquals(new InterviewStore.SessionCounts(1, 1), store.counts("alice", NOW));
        assertEquals(new InterviewStore.SessionCounts(1, 1), store.counts("bob", NOW));
    }

    @Test
    void rejectsIllegalSessionIdPaths() {
        var snapshot = sample("alice", "s-1", "start-1", NOW.plusSeconds(86400));
        assertThrows(IllegalArgumentException.class, () -> store().find("alice", "../evil"));
        assertThrows(IllegalArgumentException.class, () -> store().find("alice", "a/b"));
        assertThrows(IllegalArgumentException.class, () -> store().find("alice", ".."));
        assertThrows(IllegalArgumentException.class,
                () -> store().save(sample("alice", "..", "start-1", NOW.plusSeconds(86400))));
        // 非法访问不得产生任何文件
        assertFalse(Files.exists(dir.resolve("evil.json")));
        assertTrue(store().find("alice", "s-1").isEmpty());
    }

    @Test
    void failedWriteKeepsOldFileReadableAndCleansTempFile() {
        var original = sample("alice", "s-1", "start-1", NOW.plusSeconds(86400));
        store().save(original);

        var failing = new ObjectMapper() {
            @Override
            public void writeValue(File resultFile, Object value) throws IOException {
                throw new IOException("模拟磁盘写入失败");
            }
        }.registerModule(new JavaTimeModule());
        var failingStore = new FileSystemInterviewStore(dir, failing);

        var error = assertThrows(InterviewException.class, () -> failingStore.save(original));
        assertEquals(InterviewFailure.Code.STORAGE, error.failure().code());

        assertEquals(original, store().find("alice", "s-1").orElseThrow());
        try (var files = Files.list(dir.resolve("alice"))) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "故障写入不得残留临时文件");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void historyIndexWriteFailureKeepsCommittedSnapshotVisible() throws IOException {
        Path owner = dir.resolve("alice");
        Files.createDirectories(owner.resolve("history-index.json"));
        var snapshot = sample("alice", "s-index-failure", "start-index-failure", NOW.plusSeconds(86400));

        assertDoesNotThrow(() -> store().save(snapshot));
        assertEquals(snapshot, store().find("alice", snapshot.sessionId()).orElseThrow());
        assertTrue(store().list("alice", 20, null, NOW).items().stream()
                .anyMatch(item -> item.id().equals(snapshot.sessionId())));
    }

    @Test
    void corruptedOrUnknownVersionFailsControlledWithoutOverwriting() throws IOException {
        store().save(sample("alice", "s-1", "start-1", NOW.plusSeconds(86400)));
        Path file = dir.resolve("alice").resolve("s-1.json");

        Files.writeString(file, "not json at all");
        var corrupt = assertThrows(InterviewException.class, () -> store().find("alice", "s-1"));
        assertEquals(InterviewFailure.Code.STORAGE, corrupt.failure().code());
        assertEquals("not json at all", Files.readString(file), "损坏文件不得被覆盖");

        store().save(sample("alice", "s-2", "start-2", NOW.plusSeconds(86400)));
        Path versioned = dir.resolve("alice").resolve("s-2.json");
        Files.writeString(versioned,
                Files.readString(versioned).replace("\"schemaVersion\":2", "\"schemaVersion\":99"));
        var unknown = assertThrows(InterviewException.class, () -> store().find("alice", "s-2"));
        assertEquals(InterviewFailure.Code.STORAGE, unknown.failure().code());
    }

    @Test
    void findByStartRequestIdRoundTripsAcrossRestart() {
        store().save(sample("alice", "s-1", "start-1", NOW.plusSeconds(86400)));

        assertEquals("s-1", store().findByStartRequestId("alice", "start-1").orElseThrow().sessionId());
        assertTrue(store().findByStartRequestId("alice", "other").isEmpty());
        assertTrue(store().findByStartRequestId("alice", null).isEmpty());

        var reopened = new FileSystemInterviewStore(dir, mapper());
        assertEquals("s-1", reopened.findByStartRequestId("alice", "start-1").orElseThrow().sessionId());
    }

    @Test
    void planPreviewRoundTripsAcrossRestartAndIsIsolatedByOwner() {
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "mat-1");
        var request = new InterviewPlanService.PlanRequest(source, "后端工程师", "JD",
                InterviewPlan.CareerStage.CAMPUS, InterviewPlan.Level.STANDARD, InterviewPlan.Mode.QUICK,
                3, InterviewPlanService.SpecialtyPreference.AUTO, "plan-request-1");
        var resolved = new InterviewSourceResolver.ResolvedSource(source, "CV.txt", "简历", "fp-1");
        var preview = new InterviewPlanService.PlanPreview("plan-1", 1, NOW.plusSeconds(900),
                InterviewPlanService.PlanStatus.READY, null, null);
        var stored = new InterviewPlanService.StoredPlan("alice", "plan-request-1", request,
                new InterviewPlanService.PlanSource(resolved),
                preview, "", "");

        store().savePlan("alice", stored.withStart("start-request-1", "session-1"));

        var reopened = new FileSystemInterviewStore(dir, mapper());
        var started = stored.withStart("start-request-1", "session-1");
        assertEquals(started, reopened.findPlan("alice", "plan-1").orElseThrow());
        assertEquals(started, reopened.findPlanByRequestId("alice", "plan-request-1").orElseThrow());
        assertTrue(reopened.findPlan("bob", "plan-1").isEmpty());
        assertTrue(reopened.findPlanByRequestId("bob", "plan-request-1").isEmpty());
        try {
            String persisted = Files.readString(dir.resolve("alice/plans/plan-1.json"));
            assertFalse(persisted.contains("简历"), "plan records keep source fingerprints, not resume text");
        } catch (IOException e) { throw new AssertionError(e); }
    }

    @Test
    void deleteExpiredRemovesOnlyExpiredSnapshotsAcrossOwners() {
        store().save(sample("alice", "expired", "req-e", NOW.minusSeconds(60)));
        store().save(sample("alice", "active", "req-a", NOW.plusSeconds(3600)));
        store().save(sample("bob", "expired-b", "req-b", NOW.minusSeconds(60)));

        assertEquals(2, store().deleteExpired(NOW));

        assertTrue(store().find("alice", "expired").isEmpty());
        assertTrue(store().find("alice", "active").isPresent());
        assertTrue(store().find("bob", "expired-b").isEmpty());
    }
}
