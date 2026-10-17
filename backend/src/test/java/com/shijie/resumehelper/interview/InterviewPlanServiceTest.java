package com.shijie.resumehelper.interview;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InterviewPlanServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private final InterviewPlan.ResumeSource source = new InterviewPlan.ResumeSource(
            InterviewPlan.SourceKind.MATERIAL, "m1");
    private final InterviewSourceResolver.ResolvedSource resolved = new InterviewSourceResolver.ResolvedSource(
            source, "CV.txt", "Backend engineer with Java and SQL experience", "fingerprint-1");

    @Test
    void requestIdDeduplicatesSameOwnerAndDifferentOwnersRemainIsolated() {
        var store = new MemoryStore();
        var generated = new AtomicInteger();
        var service = service(store, generated, Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC));
        var request = request("req-1");

        var first = service.prepare("alice", request, null);
        var replay = service.prepare("alice", request, null);
        var otherOwner = service.prepare("bob", request, null);

        assertEquals(first.planId(), replay.planId());
        assertNotEquals(first.planId(), otherOwner.planId());
        assertEquals(2, generated.get());
    }

    @Test
    void generatedPlanIsReadyBoundedAndExpiresFifteenMinutesAfterCreation() {
        var store = new MemoryStore();
        var service = service(store, new AtomicInteger(), Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC));

        var preview = service.prepare("alice", request("req-2"), null);

        assertEquals(InterviewPlanService.PlanStatus.READY, preview.status());
        assertNotNull(preview.plan());
        assertTrue(preview.plan().coverage().size() <= 6);
        assertEquals(NOW.plusSeconds(900), preview.expiresAt());
        assertSame(preview, service.getPlan("alice", preview.planId()));
        assertThrows(InterviewException.class, () -> service.getPlan("bob", preview.planId()));
    }

    @Test
    void expiredPreviewCannotBeReadOrStartedAndRequestIdCannotChangePayload() {
        var store = new MemoryStore();
        var time = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        var clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
        var service = service(store, new AtomicInteger(), Runnable::run, clock);
        var preview = service.prepare("alice", request("req-3"), null);
        assertThrows(InterviewException.class, () -> service.prepare("alice", request("req-3").withTargetRole("Changed"), null));
        time.set(NOW.plusSeconds(901));
        assertEquals(InterviewPlanService.PlanStatus.EXPIRED, service.getPlan("alice", preview.planId()).status());
    }

    @Test
    void preparationReturnsPollableStateAndGetNeverRegenerates() {
        var store = new MemoryStore();
        var generated = new AtomicInteger();
        var queue = new ManualExecutor();
        var service = service(store, generated, queue, Clock.fixed(NOW, ZoneOffset.UTC));

        var accepted = service.prepare("alice", request("req-async"), null);

        assertEquals(InterviewPlanService.PlanStatus.GENERATING, accepted.status());
        assertEquals(InterviewPlanService.PlanStatus.GENERATING,
                service.getPlan("alice", accepted.planId()).status());
        assertEquals(0, generated.get());
        assertEquals(1, queue.tasks.size());
        queue.tasks.remove(0).run();
        assertEquals(InterviewPlanService.PlanStatus.READY, service.getPlan("alice", accepted.planId()).status());
        assertEquals(1, generated.get());
    }

    @Test
    void generatingPlanAfterServiceRestartBecomesRetryableFailureWithoutRegeneration() {
        var store = new MemoryStore();
        var generated = new AtomicInteger();
        var queue = new ManualExecutor();
        var first = service(store, generated, queue, Clock.fixed(NOW, ZoneOffset.UTC));
        var accepted = first.prepare("alice", request("req-restart"), null);

        var restarted = service(store, generated, new ManualExecutor(), Clock.fixed(NOW, ZoneOffset.UTC));
        var recovered = restarted.getPlan("alice", accepted.planId());

        assertEquals(InterviewPlanService.PlanStatus.FAILED, recovered.status());
        assertTrue(recovered.failure().retryable());
        assertEquals(0, generated.get());
    }

    @Test
    void rejectedPlanQueueReturnsRetryableFailureAndNewRequestCanRetry() {
        var store = new MemoryStore();
        var generated = new AtomicInteger();
        var rejected = service(store, generated, command -> { throw new java.util.concurrent.RejectedExecutionException(); },
                Clock.fixed(NOW, ZoneOffset.UTC));

        var failed = rejected.prepare("alice", request("req-rejected"), null);

        assertEquals(InterviewPlanService.PlanStatus.FAILED, failed.status());
        assertTrue(failed.failure().retryable());
        var retried = service(store, generated, Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC))
                .prepare("alice", request("req-retry"), null);
        assertEquals(InterviewPlanService.PlanStatus.READY, retried.status());
    }

    @Test
    void generatedPlanWithMoreThanSixCoverageAreasIsRejected() {
        var store = new MemoryStore();
        var excessiveCoverage = java.util.stream.IntStream.range(0, 7)
                .mapToObj(index -> new InterviewPlan.Coverage("area-" + index, "方向" + index)).toList();
        var invalid = new InterviewPlanService(store, (owner, candidate) -> resolved,
                (request, source, ai) -> new InterviewPlan("experience-v1", request.mode(), 3, 3, 2, 1,
                        request.level(), request.careerStage(), request.targetRole(), request.jobDescription(),
                        new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "", ""), excessiveCoverage,
                        source.source(), source.sourceLabel(), source.sourceFingerprint(), List.of()),
                Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC));

        var preview = invalid.prepare("alice", request("req-seven-areas"), null);

        assertEquals(InterviewPlanService.PlanStatus.FAILED, preview.status());
        assertEquals(InterviewFailure.Code.INVALID_OUTPUT, preview.failure().code());
    }

    @Test
    void confirmedPlanStartsOnceAndSameRequestReplaysTheSameSession() {
        var store = new MemoryStore();
        var sourceNow = new java.util.concurrent.atomic.AtomicReference<>(resolved);
        var starts = new AtomicInteger();
        var session = new InterviewService.Session("session-1", "m1", "数据分析师", 13,
                "ACTIVE", null, 0, 0, List.of(), null, null, null, java.util.Map.of(), null, null);
        var service = service(store, new AtomicInteger(), Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC),
                (owner, value) -> sourceNow.get(), new InterviewPlanService.PlanStartHandler() {
                    @Override public InterviewService.Session start(String owner, InterviewSourceResolver.ResolvedSource source,
                            InterviewPlan plan, String requestId, com.shijie.resumehelper.common.ai.AiClient ai) { starts.incrementAndGet(); return session; }
                    @Override public InterviewService.Session get(String owner, String sessionId) { return session; }
                });
        var preview = service.prepare("alice", request("req-start-preview"), null);

        assertSame(session, service.startPlanned("alice", preview.planId(), 1, "start-1", null));
        assertSame(session, service.startPlanned("alice", preview.planId(), 1, "start-1", null));
        assertEquals(1, starts.get());
        assertThrows(InterviewException.class,
                () -> service.startPlanned("alice", preview.planId(), 1, "different-start", null));
    }

    @Test
    void plannedStartRejectsStaleVersionAndChangedSourceBeforeCreatingSession() {
        var store = new MemoryStore();
        var sourceNow = new java.util.concurrent.atomic.AtomicReference<>(resolved);
        var starts = new AtomicInteger();
        var service = service(store, new AtomicInteger(), Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC),
                (owner, value) -> sourceNow.get(), new InterviewPlanService.PlanStartHandler() {
                    @Override public InterviewService.Session start(String owner, InterviewSourceResolver.ResolvedSource value,
                            InterviewPlan plan, String requestId, com.shijie.resumehelper.common.ai.AiClient ai) {
                        starts.incrementAndGet(); return null;
                    }
                    @Override public InterviewService.Session get(String owner, String sessionId) { return null; }
                });
        var preview = service.prepare("alice", request("req-start-stale"), null);

        assertThrows(InterviewException.class,
                () -> service.startPlanned("alice", preview.planId(), 2, "start-stale-version", null));
        sourceNow.set(new InterviewSourceResolver.ResolvedSource(source, "CV.txt", "Changed resume", "fingerprint-2"));
        assertThrows(InterviewException.class,
                () -> service.startPlanned("alice", preview.planId(), 1, "start-changed-source", null));
        assertEquals(0, starts.get());
    }

    @Test
    void focusedPlanRequestsAreRestrictedToOneToThreeQuickTargetsAndNoSpecialty() {
        var service = new InterviewPlanService(new MemoryStore(), (owner, candidate) -> resolved, (request, value, ai) ->
                new InterviewPlan("experience-v1", request.mode(), 3, 3, 2, 1, request.level(), request.careerStage(),
                        request.targetRole(), request.jobDescription(),
                        new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "无", "不使用专项"),
                        List.of(new InterviewPlan.Coverage("evidence", "结果证据")), value.source(), value.sourceLabel(),
                        value.sourceFingerprint(), request.practiceTargets(), request.sourceSessionId()),
                Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC));
        var target = new InterviewPlan.PracticeTarget("q1", "证据不足", "补充真实结果", List.of("q1"));
        var focused = new InterviewPlanService.PlanRequest(source, "数据分析师", "", InterviewPlan.CareerStage.CAMPUS,
                InterviewPlan.Level.STANDARD, InterviewPlan.Mode.QUICK, 3, InterviewPlanService.SpecialtyPreference.NONE,
                "focused-valid", "source-1", List.of(target));
        var preview = service.prepare("alice", focused, null);
        assertEquals(InterviewPlanService.PlanStatus.READY, preview.status());
        assertEquals(preview.planId(), service.prepare("alice", focused, null).planId(),
                "replaying a focused request ID must return the same preview");
        assertThrows(IllegalArgumentException.class, () -> service.prepare("alice", new InterviewPlanService.PlanRequest(
                source, "数据分析师", "", InterviewPlan.CareerStage.CAMPUS, InterviewPlan.Level.STANDARD,
                InterviewPlan.Mode.QUICK, 5, InterviewPlanService.SpecialtyPreference.NONE, "focused-five",
                "source-1", List.of(target)), null));
        assertThrows(IllegalArgumentException.class, () -> service.prepare("alice", new InterviewPlanService.PlanRequest(
                source, "数据分析师", "", InterviewPlan.CareerStage.CAMPUS, InterviewPlan.Level.STANDARD,
                InterviewPlan.Mode.FULL, 12, InterviewPlanService.SpecialtyPreference.CASE_STUDY, "focused-full",
                "source-1", List.of(target)), null));
        assertThrows(IllegalArgumentException.class, () -> service.prepare("alice", new InterviewPlanService.PlanRequest(
                source, "数据分析师", "", InterviewPlan.CareerStage.CAMPUS, InterviewPlan.Level.STANDARD,
                InterviewPlan.Mode.QUICK, 3, InterviewPlanService.SpecialtyPreference.NONE, "target-without-source",
                "", List.of(target)), null));
    }

    @Test
    void focusedPreviewMustRevalidateItsSourceReviewBeforeItCanStart() {
        var store = new MemoryStore();
        var starts = new AtomicInteger();
        var sourceValid = new java.util.concurrent.atomic.AtomicBoolean(false);
        var startedSession = new java.util.concurrent.atomic.AtomicReference<InterviewService.Session>();
        var target = new InterviewPlan.PracticeTarget("q1", "证据缺口", "补充结果", List.of("q1"));
        var starter = new InterviewPlanService.PlanStartHandler() {
            @Override public InterviewService.Session start(String owner, InterviewSourceResolver.ResolvedSource value,
                    InterviewPlan plan, String requestId, com.shijie.resumehelper.common.ai.AiClient ai) {
                starts.incrementAndGet();
                var session = new InterviewService.Session("practice-1", "m1", "工程师", 3, "ACTIVE", null,
                        0, 0, List.of(), null, null, null, java.util.Map.of(), null, plan);
                startedSession.set(session); return session;
            }
            @Override public InterviewService.Session get(String owner, String sessionId) { return startedSession.get(); }
            @Override public void validatePracticeSource(String owner, String sessionId, List<InterviewPlan.PracticeTarget> targets) {
                assertEquals("review-session", sessionId); assertEquals(List.of(target), targets);
                if (!sourceValid.get()) throw new InterviewException(409, "来源报告重点已变化");
            }
        };
        var service = new InterviewPlanService(store, (owner, candidate) -> resolved, (request, resolvedSource, ai) ->
                new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 3, 3, 2, 1,
                        request.level(), request.careerStage(), request.targetRole(), request.jobDescription(),
                        new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "无", "无需专项"),
                        List.of(new InterviewPlan.Coverage("evidence", "结果证据")), resolvedSource.source(),
                        resolvedSource.sourceLabel(), resolvedSource.sourceFingerprint(), request.practiceTargets(), request.sourceSessionId()),
                Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC), starter);
        var request = new InterviewPlanService.PlanRequest(source, "工程师", "", InterviewPlan.CareerStage.CAMPUS,
                InterviewPlan.Level.STANDARD, InterviewPlan.Mode.QUICK, 3, InterviewPlanService.SpecialtyPreference.NONE,
                "focused-plan", "review-session", List.of(target));
        var preview = service.prepare("alice", request, null);

        assertEquals(InterviewPlanService.PlanStatus.READY, preview.status());
        assertThrows(InterviewException.class, () -> service.startPlanned("alice", preview.planId(), 1, "start-1", null));
        assertEquals(0, starts.get());
        sourceValid.set(true);
        var started = service.startPlanned("alice", preview.planId(), 1, "start-1", null);
        assertEquals("practice-1", started.sessionId());
        assertEquals("review-session", started.plan().sourceSessionId());
        assertEquals(List.of(target), started.plan().practiceTargets());
        sourceValid.set(false);
        assertSame(started, service.startPlanned("alice", preview.planId(), 1, "start-1", null));
        assertEquals(1, starts.get(), "a started practice round is independent from its source report");
    }

    private InterviewPlanService service(MemoryStore store, AtomicInteger generated, Executor executor, Clock clock) {
        return service(store, generated, executor, clock, (owner, candidate) -> {
            assertEquals(source, candidate);
            return resolved;
        }, null);
    }

    private InterviewPlanService service(MemoryStore store, AtomicInteger generated, Executor executor, Clock clock,
            InterviewPlanService.SourceResolver sourceResolver, InterviewPlanService.PlanStartHandler starter) {
        return new InterviewPlanService(store, sourceResolver, (request, resume, ai) -> {
            generated.incrementAndGet();
            return new InterviewPlan("experience-v1", request.mode(), request.mainCount(), request.mainCount() + 1,
                    request.mode() == InterviewPlan.Mode.QUICK ? 2 : 6,
                    request.mode() == InterviewPlan.Mode.QUICK ? 1 : 2, request.level(), request.careerStage(),
                    request.targetRole(), request.jobDescription(),
                    new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.CASE_STUDY, "增长分析", "运营岗位"),
                    List.of(new InterviewPlan.Coverage("resume-experience", "项目实践")), resume.source(),
                    resume.sourceLabel(), resume.sourceFingerprint(), List.of());
        }, executor, clock, starter);
    }

    private InterviewPlanService.PlanRequest request(String requestId) {
        return new InterviewPlanService.PlanRequest(source, "数据分析师", "完整岗位描述", 
                InterviewPlan.CareerStage.CAMPUS, InterviewPlan.Level.STANDARD,
                InterviewPlan.Mode.QUICK, 3, InterviewPlanService.SpecialtyPreference.AUTO, requestId);
    }

    private static final class MemoryStore implements InterviewStore {
        private final java.util.Map<String, InterviewPlanService.StoredPlan> plans = new java.util.HashMap<>();
        @Override public void save(InterviewSnapshot snapshot) { }
        @Override public Optional<InterviewSnapshot> find(String owner, String id) { return Optional.empty(); }
        @Override public Optional<InterviewSnapshot> findByStartRequestId(String owner, String requestId) { return Optional.empty(); }
        @Override public int deleteExpired(Instant now) { return 0; }
        @Override public void savePlan(String owner, InterviewPlanService.StoredPlan plan) { plans.put(owner + "/" + plan.preview().planId(), plan); }
        @Override public Optional<InterviewPlanService.StoredPlan> findPlan(String owner, String planId) { return Optional.ofNullable(plans.get(owner + "/" + planId)); }
        @Override public Optional<InterviewPlanService.StoredPlan> findPlanByRequestId(String owner, String requestId) {
            return plans.values().stream().filter(p -> p.owner().equals(owner) && p.requestId().equals(requestId)).findFirst();
        }
    }

    private static final class ManualExecutor implements Executor {
        private final java.util.List<Runnable> tasks = new java.util.ArrayList<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
    }
}
