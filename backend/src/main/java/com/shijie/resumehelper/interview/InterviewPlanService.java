package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Creates durable, owner-scoped plan previews without doing model I/O on the request thread. */
public class InterviewPlanService {
    public static final long PLAN_TTL_SECONDS = 15 * 60;
    private static final int MAX_COVERAGE = 6;

    public enum SpecialtyPreference { AUTO, NONE, ALGORITHM, CASE_STUDY }
    public enum PlanStatus { GENERATING, READY, FAILED, EXPIRED }

    public record PlanRequest(InterviewPlan.ResumeSource source, String targetRole, String jobDescription,
                             InterviewPlan.CareerStage careerStage, InterviewPlan.Level level,
                             InterviewPlan.Mode mode, Integer mainCount,
                             SpecialtyPreference specialtyPreference, String requestId,
                             String sourceSessionId, List<InterviewPlan.PracticeTarget> practiceTargets) {
        public PlanRequest {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(careerStage, "careerStage");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(specialtyPreference, "specialtyPreference");
            targetRole = targetRole == null ? "" : targetRole.strip();
            jobDescription = jobDescription == null ? "" : jobDescription.strip();
            requestId = requestId == null ? "" : requestId.strip();
            sourceSessionId = sourceSessionId == null ? "" : sourceSessionId.strip();
            practiceTargets = practiceTargets == null ? List.of() : List.copyOf(practiceTargets);
        }

        public PlanRequest(InterviewPlan.ResumeSource source, String targetRole, String jobDescription,
                InterviewPlan.CareerStage careerStage, InterviewPlan.Level level, InterviewPlan.Mode mode,
                Integer mainCount, SpecialtyPreference specialtyPreference, String requestId) {
            this(source, targetRole, jobDescription, careerStage, level, mode, mainCount, specialtyPreference,
                    requestId, "", List.of());
        }

        public PlanRequest withTargetRole(String value) {
            return new PlanRequest(source, value, jobDescription, careerStage, level, mode, mainCount,
                    specialtyPreference, requestId, sourceSessionId, practiceTargets);
        }
    }

    public record PlanPreview(String planId, int version, Instant expiresAt, PlanStatus status,
                              InterviewPlan plan, InterviewFailure failure) { }

    public record PlanSource(InterviewPlan.ResumeSource source, String sourceLabel, String sourceFingerprint) {
        public PlanSource(InterviewSourceResolver.ResolvedSource resolved) {
            this(resolved.source(), resolved.sourceLabel(), resolved.sourceFingerprint());
        }
    }

    /** Persisted wrapper keeps deduplication and later start idempotency metadata out of the user DTO. */
    public record StoredPlan(String owner, String requestId, PlanRequest request,
                             PlanSource source,
                             PlanPreview preview, String startRequestId, String startSessionId) {
        public StoredPlan withPreview(PlanPreview value) {
            return new StoredPlan(owner, requestId, request, source, value, startRequestId, startSessionId);
        }
        public StoredPlan withStart(String request, String session) {
            return new StoredPlan(owner, requestId, this.request, source, preview, request, session);
        }
    }

    @FunctionalInterface public interface SourceResolver {
        InterviewSourceResolver.ResolvedSource resolve(String owner, InterviewPlan.ResumeSource source);
    }
    @FunctionalInterface public interface PlanGenerator {
        InterviewPlan generate(PlanRequest request, InterviewSourceResolver.ResolvedSource source, AiClient ai);
    }
    public interface PlanStartHandler {
        InterviewService.Session start(String owner, InterviewSourceResolver.ResolvedSource source,
                                       InterviewPlan plan, String requestId, AiClient ai);
        default InterviewService.Session start(String owner, InterviewSourceResolver.ResolvedSource source,
                InterviewPlan plan, String requestId, AiClient ai, boolean allowAlgorithmRepeat) {
            return start(owner, source, plan, requestId, ai);
        }
        InterviewService.Session get(String owner, String sessionId);
        default void validatePracticeSource(String owner, String sessionId, List<InterviewPlan.PracticeTarget> targets) { }
    }

    private final InterviewStore store;
    private final SourceResolver sources;
    private final PlanGenerator generator;
    private final Executor executor;
    private final Clock clock;
    private final PlanStartHandler starter;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentMap<String, InterviewSourceResolver.ResolvedSource> generationSources = new ConcurrentHashMap<>();

    public InterviewPlanService(InterviewStore store, SourceResolver sources, PlanGenerator generator,
                                Executor executor, Clock clock) {
        this(store, sources, generator, executor, clock, null);
    }

    public InterviewPlanService(InterviewStore store, SourceResolver sources, PlanGenerator generator,
                                Executor executor, Clock clock, PlanStartHandler starter) {
        this.store = Objects.requireNonNull(store);
        this.sources = Objects.requireNonNull(sources);
        this.generator = Objects.requireNonNull(generator);
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
        this.starter = starter;
    }

    public synchronized PlanPreview prepare(String owner, PlanRequest request, AiClient ai) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("用户标识不能为空");
        validateRequest(request);
        Optional<StoredPlan> prior = store.findPlanByRequestId(owner, request.requestId());
        if (prior.isPresent()) {
            StoredPlan stored = prior.get();
            if (!stored.request().equals(request)) throw new InterviewException(409, "请求 ID 已用于不同的面试计划");
            return current(stored);
        }
        var source = sources.resolve(owner, request.source());
        Instant now = clock.instant();
        var preview = new PlanPreview(UUID.randomUUID().toString(), 1, now.plusSeconds(PLAN_TTL_SECONDS),
                PlanStatus.GENERATING, null, null);
        var stored = new StoredPlan(owner, request.requestId(), request, new PlanSource(source), preview, "", "");
        store.savePlan(owner, stored);
        try {
            generationSources.put(preview.planId(), source);
            inFlight.add(preview.planId());
            executor.execute(() -> generate(owner, preview.planId(), ai));
        } catch (RuntimeException rejected) {
            generationSources.remove(preview.planId());
            inFlight.remove(preview.planId());
            fail(stored, new InterviewFailure(InterviewFailure.Code.UPSTREAM_UNAVAILABLE, true,
                    "计划暂时无法排队，请使用当前凭据重试", null));
        }
        return getPlan(owner, preview.planId());
    }

    public PlanPreview getPlan(String owner, String planId) {
        StoredPlan stored = store.findPlan(owner, planId)
                .orElseThrow(() -> new InterviewException(404, "面试计划不存在或已过期"));
        return current(stored);
    }

    public synchronized InterviewService.Session startPlanned(String owner, String planId, int version,
                                                               String requestId, AiClient ai) {
        return startPlanned(owner, planId, version, requestId, ai, false);
    }

    public synchronized InterviewService.Session startPlanned(String owner, String planId, int version,
            String requestId, AiClient ai, boolean allowAlgorithmRepeat) {
        if (starter == null) throw new IllegalStateException("计划启动器未配置");
        if (requestId == null || requestId.isBlank() || requestId.length() > 120)
            throw new IllegalArgumentException("开始请求 ID 不能为空且不能超过 120 字");
        StoredPlan stored = store.findPlan(owner, planId)
                .orElseThrow(() -> new InterviewException(404, "面试计划不存在或已过期"));
        if (!stored.startSessionId().isBlank()) {
            if (!stored.startRequestId().equals(requestId))
                throw new InterviewException(409, "此计划已经用于开始另一轮面试");
            return starter.get(owner, stored.startSessionId());
        }
        PlanPreview preview = current(stored);
        if (preview.status() != PlanStatus.READY || preview.plan() == null)
            throw new InterviewException(409, "计划尚未准备完成，请刷新状态后重试");
        if (!stored.request().sourceSessionId().isBlank())
            starter.validatePracticeSource(owner, stored.request().sourceSessionId(), stored.request().practiceTargets());
        if (version != preview.version()) throw new InterviewException(409, "计划版本已变化，请重新确认");
        InterviewSourceResolver.ResolvedSource currentSource = sources.resolve(owner, stored.source().source());
        if (!stored.source().sourceFingerprint().equals(currentSource.sourceFingerprint())
                || !stored.source().sourceLabel().equals(currentSource.sourceLabel()))
            throw new InterviewException(409, "简历来源已变化，请重新预览计划");
        InterviewService.Session session = starter.start(owner, currentSource, preview.plan(), requestId, ai, allowAlgorithmRepeat);
        store.savePlan(owner, stored.withStart(requestId, session.sessionId()));
        return session;
    }

    private PlanPreview current(StoredPlan stored) {
        PlanPreview preview = stored.preview();
        if (!clock.instant().isBefore(preview.expiresAt())) {
            var expired = new PlanPreview(preview.planId(), preview.version(), preview.expiresAt(), PlanStatus.EXPIRED,
                    null, null);
            if (preview.status() != PlanStatus.EXPIRED) store.savePlan(stored.owner(), stored.withPreview(expired));
            return expired;
        }
        // In-flight work cannot survive process restart. GET does not regenerate or extend the TTL.
        if (preview.status() == PlanStatus.GENERATING && !inFlight.contains(preview.planId())) {
            var failed = new PlanPreview(preview.planId(), preview.version(), preview.expiresAt(), PlanStatus.FAILED,
                    null, new InterviewFailure(InterviewFailure.Code.INTERRUPTED, true,
                    "计划生成已中断，请使用当前凭据重新预览", null));
            store.savePlan(stored.owner(), stored.withPreview(failed));
            return failed;
        }
        return preview;
    }

    private void generate(String owner, String planId, AiClient ai) {
        StoredPlan stored;
        try {
            stored = store.findPlan(owner, planId).orElse(null);
            if (stored == null || stored.preview().status() != PlanStatus.GENERATING
                    || !clock.instant().isBefore(stored.preview().expiresAt())) return;
            var source = generationSources.get(planId);
            if (source == null) {
                fail(stored, new InterviewFailure(InterviewFailure.Code.INTERRUPTED, true,
                        "计划生成已中断，请使用当前凭据重新预览", null));
                return;
            }
            InterviewPlan plan = generator.generate(stored.request(), source, ai);
            validateGeneratedPlan(stored, plan);
            var ready = new PlanPreview(planId, stored.preview().version(), stored.preview().expiresAt(),
                    PlanStatus.READY, plan, null);
            store.savePlan(owner, stored.withPreview(ready));
        } catch (InterviewException e) {
            stored = store.findPlan(owner, planId).orElse(null);
            if (stored != null) fail(stored, e.failure() == null
                    ? new InterviewFailure(InterviewFailure.Code.UNKNOWN, true, e.getMessage(), null) : e.failure());
        } catch (RuntimeException e) {
            stored = store.findPlan(owner, planId).orElse(null);
            if (stored != null) fail(stored, new InterviewFailure(InterviewFailure.Code.UNKNOWN, true,
                    "面试计划生成失败，请使用当前凭据重试", null));
        } finally { inFlight.remove(planId); generationSources.remove(planId); }
    }

    private void fail(StoredPlan stored, InterviewFailure failure) {
        var previous = stored.preview();
        store.savePlan(stored.owner(), stored.withPreview(new PlanPreview(previous.planId(), previous.version(),
                previous.expiresAt(), PlanStatus.FAILED, null, failure)));
    }

    private static void validateRequest(PlanRequest request) {
        if (request == null || request.requestId().isBlank() || request.requestId().length() > 120)
            throw new IllegalArgumentException("计划请求 ID 不能为空且不能超过 120 字");
        if (request.targetRole().length() > 200) throw new IllegalArgumentException("目标岗位不能超过 200 字");
        if (request.jobDescription().length() > 8000) throw new IllegalArgumentException("岗位描述不能超过 8000 字");
        if (request.sourceSessionId().length() > 120) throw new IllegalArgumentException("来源轮次 ID 无效");
        if (!request.practiceTargets().isEmpty()) {
            if (request.mode() != InterviewPlan.Mode.QUICK || request.mainCount() != null && request.mainCount() != 3
                    || request.specialtyPreference() != SpecialtyPreference.NONE
                    || request.practiceTargets().isEmpty() || request.practiceTargets().size() > 3
                    || request.sourceSessionId().isBlank())
                throw new IllegalArgumentException("针对薄弱项的练习必须关联来源轮次并使用三题 QUICK 计划");
            var ids = new java.util.HashSet<String>();
            for (var target : request.practiceTargets()) {
                if (target.issueId().isBlank() || !ids.add(target.issueId()) || target.sourceQuestionIds().isEmpty())
                    throw new IllegalArgumentException("薄弱项练习目标无效或重复");
                InterviewQuestion.requireText(target.title(), 240, "练习目标");
                InterviewQuestion.requireText(target.action(), 300, "练习动作");
            }
        } else if (!request.sourceSessionId().isBlank()) {
            throw new IllegalArgumentException("来源轮次与练习目标不匹配");
        }
        int count = request.mainCount() == null
                ? request.mode() == InterviewPlan.Mode.FULL ? 12 : 5 : request.mainCount();
        if (request.mode() == InterviewPlan.Mode.QUICK && count != 3 && count != 5)
            throw new IllegalArgumentException("快速练习只支持 3 或 5 道主问题");
        if (request.mode() == InterviewPlan.Mode.FULL && count != 12)
            throw new IllegalArgumentException("完整练习固定为 12 道主问题");
        if (request.mode() == InterviewPlan.Mode.FULL && request.specialtyPreference() == SpecialtyPreference.NONE)
            throw new IllegalArgumentException("完整练习必须包含岗位专项题");
    }

    private static void validateGeneratedPlan(StoredPlan stored, InterviewPlan plan) {
        PlanRequest request = stored.request();
        if (plan == null || !"experience-v1".equals(plan.policyVersion()) || plan.coverage().size() > MAX_COVERAGE
                || !plan.source().equals(stored.source().source())
                || !plan.sourceFingerprint().equals(stored.source().sourceFingerprint())
                || !plan.targetRole().equals(request.targetRole()) || !plan.jobDescription().equals(request.jobDescription())
                || plan.mode() != request.mode() || plan.mainCount() != (request.mainCount() == null
                    ? request.mode() == InterviewPlan.Mode.FULL ? 12 : 5 : request.mainCount())
                || plan.plannedCount() != plan.mainCount()
                    + (plan.specialty().kind() == InterviewPlan.SpecialtyKind.NONE ? 0 : 1)
                || plan.followUpBudget() != (request.mode() == InterviewPlan.Mode.QUICK ? 2 : 6)
                || plan.maxFollowUpsPerMain() != (request.mode() == InterviewPlan.Mode.QUICK ? 1 : 2)
                || plan.level() != request.level() || plan.careerStage() != request.careerStage())
            throw invalidPlan("模型生成的计划与请求不一致");
        if (!request.practiceTargets().equals(plan.practiceTargets()))
            throw invalidPlan("计划没有保留已验证的练习目标");
        if (!request.sourceSessionId().equals(plan.sourceSessionId()))
            throw invalidPlan("计划没有保留来源轮次");
        if (request.mode() == InterviewPlan.Mode.FULL && plan.specialty().kind() == InterviewPlan.SpecialtyKind.NONE)
            throw invalidPlan("完整练习计划必须包含岗位专项题");
        if (request.specialtyPreference() != SpecialtyPreference.AUTO) {
            InterviewPlan.SpecialtyKind expected = switch (request.specialtyPreference()) {
                case NONE -> InterviewPlan.SpecialtyKind.NONE;
                case ALGORITHM -> InterviewPlan.SpecialtyKind.ALGORITHM;
                case CASE_STUDY -> InterviewPlan.SpecialtyKind.CASE_STUDY;
                case AUTO -> plan.specialty().kind();
            };
            if (plan.specialty().kind() != expected) throw invalidPlan("专项类型与用户选择不一致");
        }
    }

    private static InterviewException invalidPlan(String message) {
        return new InterviewException(502, message,
                new InterviewFailure(InterviewFailure.Code.INVALID_OUTPUT, true, "模型返回的计划无效，请重试", null));
    }
}
