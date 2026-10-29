package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class InterviewService {
    private static final Logger LOG = LoggerFactory.getLogger(InterviewService.class);
    private static final Hot100Catalog HOT100 = new Hot100Catalog();
    public static final int MAIN_COUNT = 12, QUESTION_COUNT = 13;
    public enum Kind { MAIN, FOLLOW_UP, ALGORITHM, CASE_STUDY;
        public boolean isSpecialty() { return this == ALGORITHM || this == CASE_STUDY; }
    }
    public record Context(Kind kind, String parentQuestionId, int mainQuestionIndex,
                          Hot100Catalog.Algorithm algorithm) {}
    private final InterviewAgent agent;
    /** 持久化存储；为 null 时退化为纯内存（兼容旧的不带 store 的单元测试）。 */
    private final InterviewStore store;
    private final Clock clock;
    private final InterviewProperties properties;
    private final RetrySleeper retrySleeper;
    private final Map<String, State> sessions = new HashMap<>();

    /** 生产构造器：Spring 注入 store 与 clock。 */
    @Autowired
    public InterviewService(InterviewAgent agent, InterviewStore store, Clock clock, InterviewProperties properties) {
        this(agent, store, clock, properties, Thread::sleep);
    }
    public InterviewService(InterviewAgent agent, InterviewStore store, Clock clock,
                            InterviewProperties properties, RetrySleeper retrySleeper) {
        this.agent = agent;
        this.store = store;
        this.clock = clock;
        this.properties = properties;
        this.retrySleeper = retrySleeper;
    }
    public InterviewService(InterviewAgent agent, InterviewStore store, Clock clock) {
        this(agent, store, clock, new InterviewProperties());
    }
    /** 兼容构造器：纯内存，不落盘（旧 InterviewFlowTest 等沿用）。 */
    public InterviewService(InterviewAgent agent) { this(agent, null, Clock.systemUTC()); }

    public record Turn(String questionId, String question, String answer) {}
    @FunctionalInterface public interface RetrySleeper { void sleep(long delayMs) throws InterruptedException; }
    /** 回答保存的幂等载荷（计划 4.2）。 */
    public record Submission(String questionId, String submissionId, String answer) {}
    public record ReplacementContext(String resume, String role, InterviewQuestion question) {}
    public record Session(String sessionId, String materialId, String targetRole, int questionCount,
                          String status, String finishReason, long revision, long stateVersion,
                          List<Turn> turns, Turn currentQuestion, String pendingAnswer,
                          InterviewSnapshot.Generation generation,
                          Map<String, InterviewQuestion.Overview> questionMetadata, InterviewSpecialty specialty,
                          InterviewPlan plan, InterviewSnapshot.Progress progress, Set<String> closedRootIds,
                          Map<String, InterviewSnapshot.Clarification> clarifications,
                          Set<String> withdrawnQuestionIds,
                          Map<String, InterviewSnapshot.Replacement> replacements,
                          Map<String, InterviewPracticeFeedback> practiceFeedbacks) {
        public Session {
            practiceFeedbacks = practiceFeedbacks == null ? Map.of() : Map.copyOf(practiceFeedbacks);
        }
        public Session(String sessionId, String materialId, String targetRole, int questionCount,
                String status, String finishReason, long revision, long stateVersion, List<Turn> turns,
                Turn currentQuestion, String pendingAnswer, InterviewSnapshot.Generation generation,
                Map<String, InterviewQuestion.Overview> questionMetadata, InterviewSpecialty specialty,
                InterviewPlan plan) {
            this(sessionId, materialId, targetRole, questionCount, status, finishReason, revision, stateVersion,
                    turns, currentQuestion, pendingAnswer, generation, questionMetadata, specialty, plan,
                    InterviewSnapshot.Progress.empty(), Set.of(), Map.of(), Set.of(), Map.of(), Map.of());
        }
    }

    private static final class State {
        final String id, owner, materialId, resume, role;
        int count = QUESTION_COUNT;
        Hot100Catalog.Problem selectedAlgorithm;
        InterviewSpecialty specialty;
        InterviewPlan plan;
        final Map<String, Context> contexts = new LinkedHashMap<>();
        final Set<String> closedRootIds = new LinkedHashSet<>();
        final Map<String, InterviewSnapshot.Clarification> clarifications = new LinkedHashMap<>();
        final Set<String> withdrawnQuestionIds = new LinkedHashSet<>();
        final Map<String, InterviewSnapshot.Replacement> replacements = new LinkedHashMap<>();
        final Map<String, InterviewPracticeFeedback> practiceFeedbacks = new LinkedHashMap<>();
        final Map<String, InterviewAttempt> attempts = new LinkedHashMap<>();
        InterviewReviewSummary reviewSummary;
        String reviewRequestId, reviewOperationId;
        final Instant createdAt;
        final Instant expires;
        final List<Turn> turns = new ArrayList<>();
        final Map<String, InterviewQuestion> questionContracts = new LinkedHashMap<>();
        final Map<String, InterviewAssessment> assessments = new LinkedHashMap<>();
        final Map<String, String> submissionIds = new LinkedHashMap<>();
        final Object reportLock = new Object();
        InterviewAgent.InterviewReport cachedReport;
        InterviewSnapshot.Generation generation;
        String status = "ACTIVE", pending, finishReason, currentQuestionId, startRequestId;
        String scoringState = "NOT_STARTED", scoringOperationId, scoringRequestId, scoringError;
        long scoringStateVersion = 0;
        long revision = 0, stateVersion = 0;
        State(String id, String owner, String materialId, String resume, String role, Instant createdAt, Instant expires) {
            this.id = id; this.owner = owner; this.materialId = materialId; this.resume = resume; this.role = role;
            this.createdAt = createdAt; this.expires = expires;
        }

        Session snapshot() {
            Turn current = currentQuestionId == null ? null
                    : turns.stream().filter(t -> t.questionId().equals(currentQuestionId))
                            .findFirst().orElse(null);
            var metadata = new LinkedHashMap<String, InterviewQuestion.Overview>();
            var policy = new InterviewScoringPolicy();
            var activeTurns = turns.stream().filter(t -> !withdrawnQuestionIds.contains(t.questionId())).toList();
            var shares = weightShares(activeTurns, questionContracts, contexts);
            contexts.forEach((id, context) -> {
                var q = questionContracts.get(id);
                if (q == null) return; // 恢复中或未冻结的题不进 metadata
                metadata.put(id, new InterviewQuestion.Overview(q.competency(), q.importance(), q.difficulty(),
                        context.kind() == Kind.FOLLOW_UP ? 0 : policy.weightUnits(q.importance(), q.difficulty()), q.weightReason(),
                        context.kind(), context.parentQuestionId(), context.mainQuestionIndex(), context.algorithm(), shares.get(id)));
            });
            return new Session(id, materialId, role, count, status, finishReason, revision, stateVersion,
                    List.copyOf(turns), current, pending, generation, Map.copyOf(metadata), specialty, plan,
                    progress(), Set.copyOf(closedRootIds), Map.copyOf(clarifications),
                    Set.copyOf(withdrawnQuestionIds), Map.copyOf(replacements), Map.copyOf(practiceFeedbacks));
        }

        InterviewSnapshot.Progress progress() {
            if (plan == null || !"experience-v1".equals(plan.policyVersion())) return InterviewSnapshot.Progress.empty();
            int answered = 0, resolved = 0, skipped = 0, followUps = 0;
            for (var entry : contexts.entrySet()) {
                Context context = entry.getValue();
                if (withdrawnQuestionIds.contains(entry.getKey())) continue;
                if (context.kind() == Kind.FOLLOW_UP) followUps++;
                if (context.kind() != Kind.MAIN) continue;
                Turn turn = turns.stream().filter(t -> t.questionId().equals(entry.getKey())).findFirst().orElse(null);
                boolean hasAnswer = turn != null && turn.answer() != null;
                boolean wasSkipped = closedRootIds.contains(entry.getKey()) && !hasAnswer;
                if (hasAnswer) answered++;
                if (hasAnswer || closedRootIds.contains(entry.getKey())) resolved++;
                if (wasSkipped) skipped++;
            }
            return new InterviewSnapshot.Progress(plan.mainCount(), resolved, answered, skipped,
                    followUps, plan.followUpBudget());
        }

        /** 构建可落盘快照（计划 4.4 全字段）。运行期句柄（锁、Future、客户端）不进入。 */
        InterviewSnapshot toSnapshot() {
            var snapTurns = turns.stream().map(t -> new InterviewSnapshot.SnapshotTurn(
                    t.questionId(), t.question(), t.answer(),
                    withdrawnQuestionIds.contains(t.questionId()) ? InterviewSnapshot.AnswerStatus.WITHDRAWN
                            : t.answer() != null ? InterviewSnapshot.AnswerStatus.ANSWERED
                            : isClosed(t.questionId()) ? InterviewSnapshot.AnswerStatus.SKIPPED
                            : InterviewSnapshot.AnswerStatus.UNANSWERED,
                    submissionIds.get(t.questionId()))).toList();
            var snapContexts = new LinkedHashMap<String, InterviewSnapshot.QuestionContext>();
            contexts.forEach((id, c) -> snapContexts.put(id,
                    new InterviewSnapshot.QuestionContext(c.kind(), c.parentQuestionId(), c.mainQuestionIndex(), c.algorithm())));
            return new InterviewSnapshot(InterviewSnapshot.SCHEMA_VERSION, id, owner, materialId, resume, role, count,
                    status, finishReason, pending, revision, stateVersion, snapTurns, questionContracts, snapContexts,
                    selectedAlgorithm, specialty, assessments, cachedReport, generation, startRequestId,
                    plan, progress(), Set.copyOf(closedRootIds), createdAt, expires,
                    scoringState, scoringOperationId, scoringRequestId, scoringError, scoringStateVersion,
                    clarifications, Set.copyOf(withdrawnQuestionIds), replacements, reviewSummary, reviewRequestId, reviewOperationId,
                    practiceFeedbacks, attempts);
        }

        private boolean isClosed(String questionId) {
            Context context = contexts.get(questionId);
            String rootId = context != null && context.kind() == Kind.FOLLOW_UP
                    ? context.parentQuestionId() : questionId;
            return closedRootIds.contains(rootId);
        }

        /** 从快照重建纯数据 State；运行期句柄（reportLock）新建。 */
        static State fromSnapshot(InterviewSnapshot s) {
            var st = new State(s.sessionId(), s.owner(), s.materialId(), s.resumeText(), s.targetRole(), s.createdAt(), s.expiresAt());
            st.status = s.status();
            st.finishReason = s.finishReason();
            st.pending = s.pendingAnswer();
            st.revision = s.revision();
            st.stateVersion = s.stateVersion();
            st.plan = s.plan() == null
                    ? InterviewPlan.legacyV3(new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL,
                            s.materialId() == null ? "" : s.materialId()), s.targetRole())
                    : s.plan();
            st.count = s.questionCount();
            st.closedRootIds.addAll(s.closedRootIds());
            s.practiceFeedbacks().forEach((questionId, feedback) -> st.practiceFeedbacks.put(questionId,
                    feedback.status() == InterviewPracticeFeedback.Status.QUEUED
                            || feedback.status() == InterviewPracticeFeedback.Status.RUNNING
                            ? new InterviewPracticeFeedback(feedback.requestId(), InterviewPracticeFeedback.Status.FAILED,
                                    null, "服务重启导致反馈中断，可以继续下一题或重新查看反馈")
                            : feedback));
            s.attempts().forEach((attemptId, attempt) -> st.attempts.put(attemptId,
                    attempt.status() == InterviewAttempt.Status.PENDING || attempt.status() == InterviewAttempt.Status.RUNNING
                            ? new InterviewAttempt(attempt.attemptId(), attempt.requestId(), UUID.randomUUID().toString(), attempt.questionId(), attempt.version(),
                                    attempt.answer(), InterviewAttempt.Status.FAILED, null, null, "", attempt.rubricVersion(),
                                    "服务重启导致重答评分中断，答案已保存，可补评")
                            : attempt));
            st.withdrawnQuestionIds.addAll(s.withdrawnQuestionIds());
            s.replacements().forEach((questionId, operation) -> st.replacements.put(questionId,
                    operation.status() == InterviewSnapshot.ReplacementStatus.RUNNING
                            ? new InterviewSnapshot.Replacement(operation.requestId(),
                                    InterviewSnapshot.ReplacementStatus.FAILED, operation.reason(), null,
                                    "服务重启导致换题中断，请重试")
                            : operation));
            s.clarifications().forEach((questionId, operation) -> st.clarifications.put(questionId,
                    operation.status() == InterviewSnapshot.ClarificationStatus.RUNNING
                            ? new InterviewSnapshot.Clarification(operation.requestId(),
                                    InterviewSnapshot.ClarificationStatus.FAILED, null,
                                    "服务重启导致解释中断，请重试")
                            : operation));
            st.specialty = s.specialty();
            st.selectedAlgorithm = s.selectedAlgorithm();
            st.cachedReport = s.cachedReport();
            st.reviewSummary = s.reviewSummary();
            st.reviewRequestId = s.reviewRequestId(); st.reviewOperationId = s.reviewOperationId();
            if (st.reviewSummary != null && st.reviewSummary.status() == InterviewReviewSummary.Status.PENDING) {
                st.reviewSummary = new InterviewReviewSummary(InterviewReviewSummary.Status.UNAVAILABLE,
                        st.reviewSummary.sourceVersion(), List.of(), "服务重启导致报告归纳中断，可重试。");
            }
            st.scoringState = s.scoringState() == null ? "NOT_STARTED" : s.scoringState();
            st.scoringOperationId = s.scoringOperationId();
            st.scoringRequestId = s.scoringRequestId();
            st.scoringError = s.scoringError();
            st.scoringStateVersion = s.scoringStateVersion();
            if ("QUEUED".equals(st.scoringState) || "RUNNING".equals(st.scoringState)) {
                st.scoringState = "FAILED";
                st.scoringError = "服务重启，评分任务已中断；可以重试待评分题目。";
                st.scoringStateVersion++;
            }
            // 重启不恢复在途生成：落盘时的 QUEUED/RUNNING/RETRY_WAIT 没有存活的工作线程，只保留数据。
            st.generation = s.generation() != null && isActiveGeneration(s.generation().state())
                    ? withState(s.generation(), InterviewSnapshot.GenerationState.FAILED,
                            new InterviewFailure(InterviewFailure.Code.INTERRUPTED, false, "服务重启，本次出题已中断", null))
                    : s.generation();
            st.startRequestId = s.startRequestId();
            st.currentQuestionId = null;
            s.turns().forEach(t -> {
                st.turns.add(new Turn(t.questionId(), t.question(), t.answer()));
                if (t.submissionId() != null) st.submissionIds.put(t.questionId(), t.submissionId());
            });
            s.questionContracts().forEach((id, q) -> st.questionContracts.put(id, q));
            s.contexts().forEach((id, c) -> st.contexts.put(id,
                    new Context(c.kind(), c.parentQuestionId(), c.mainQuestionIndex(), c.algorithm())));
            s.assessments().forEach((id, a) -> st.assessments.put(id, a));
            // currentQuestion：恢复时取最后一个未答题（与落盘前 snapshot 语义对齐）
            for (int i = st.turns.size() - 1; i >= 0; i--) {
                var t = st.turns.get(i);
                if (t.answer() == null && !st.withdrawnQuestionIds.contains(t.questionId()) && !st.isClosed(t.questionId())) { st.currentQuestionId = t.questionId(); break; }
            }
            return st;
        }

        /** 备份本次写入前的可变字段，用于 save 失败回滚。 */
        Backup backup() {
            return new Backup(status, pending, finishReason, currentQuestionId, revision, stateVersion,
                    new ArrayList<>(turns), new LinkedHashMap<>(submissionIds), new LinkedHashMap<>(assessments),
                    cachedReport, generation, specialty, selectedAlgorithm, plan, scoringState, scoringOperationId,
                    scoringRequestId, scoringError, scoringStateVersion, new LinkedHashSet<>(closedRootIds),
                    new LinkedHashMap<>(clarifications), new LinkedHashSet<>(withdrawnQuestionIds),
                    new LinkedHashMap<>(replacements), reviewSummary, reviewRequestId, reviewOperationId,
                    new LinkedHashMap<>(practiceFeedbacks), new LinkedHashMap<>(attempts));
        }
        void restore(Backup b) {
            status = b.status; pending = b.pending; finishReason = b.finishReason;
            currentQuestionId = b.currentQuestionId; revision = b.revision; stateVersion = b.stateVersion;
            turns.clear(); turns.addAll(b.turns);
            submissionIds.clear(); submissionIds.putAll(b.submissionIds);
            assessments.clear(); assessments.putAll(b.assessments);
            cachedReport = b.cachedReport; generation = b.generation;
            scoringState = b.scoringState; scoringOperationId = b.scoringOperationId;
            scoringRequestId = b.scoringRequestId; scoringError = b.scoringError; scoringStateVersion = b.scoringStateVersion;
            specialty = b.specialty; selectedAlgorithm = b.selectedAlgorithm;
            plan = b.plan;
            closedRootIds.clear(); closedRootIds.addAll(b.closedRootIds);
            clarifications.clear(); clarifications.putAll(b.clarifications);
            withdrawnQuestionIds.clear(); withdrawnQuestionIds.addAll(b.withdrawnQuestionIds);
            replacements.clear(); replacements.putAll(b.replacements);
            reviewSummary = b.reviewSummary; reviewRequestId = b.reviewRequestId; reviewOperationId = b.reviewOperationId;
            practiceFeedbacks.clear(); practiceFeedbacks.putAll(b.practiceFeedbacks);
            attempts.clear(); attempts.putAll(b.attempts);
        }
        private static final class Backup {
            final String status, pending, finishReason, currentQuestionId;
            final long revision, stateVersion;
            final List<Turn> turns;
            final Map<String, String> submissionIds;
            final Map<String, InterviewAssessment> assessments;
            final InterviewAgent.InterviewReport cachedReport;
            final InterviewSnapshot.Generation generation;
            final InterviewSpecialty specialty;
            final Hot100Catalog.Problem selectedAlgorithm;
            final InterviewPlan plan;
            final String scoringState, scoringOperationId, scoringRequestId, scoringError;
            final long scoringStateVersion;
            final Set<String> closedRootIds;
            final Map<String, InterviewSnapshot.Clarification> clarifications;
            final Set<String> withdrawnQuestionIds;
            final Map<String, InterviewSnapshot.Replacement> replacements;
            final InterviewReviewSummary reviewSummary;
            final String reviewRequestId, reviewOperationId;
            final Map<String, InterviewPracticeFeedback> practiceFeedbacks;
            final Map<String, InterviewAttempt> attempts;
            Backup(String status, String pending, String finishReason, String currentQuestionId,
                   long revision, long stateVersion, List<Turn> turns, Map<String, String> submissionIds,
                   Map<String, InterviewAssessment> assessments, InterviewAgent.InterviewReport cachedReport,
                   InterviewSnapshot.Generation generation, InterviewSpecialty specialty,
                   Hot100Catalog.Problem selectedAlgorithm, InterviewPlan plan, String scoringState,
                   String scoringOperationId, String scoringRequestId, String scoringError, long scoringStateVersion,
                   Set<String> closedRootIds, Map<String, InterviewSnapshot.Clarification> clarifications,
                   Set<String> withdrawnQuestionIds, Map<String, InterviewSnapshot.Replacement> replacements,
                   InterviewReviewSummary reviewSummary, String reviewRequestId, String reviewOperationId,
                   Map<String, InterviewPracticeFeedback> practiceFeedbacks, Map<String, InterviewAttempt> attempts) {
                this.status = status; this.pending = pending; this.finishReason = finishReason;
                this.currentQuestionId = currentQuestionId; this.revision = revision; this.stateVersion = stateVersion;
                this.turns = turns; this.submissionIds = submissionIds; this.assessments = assessments;
                this.cachedReport = cachedReport; this.generation = generation;
                this.specialty = specialty; this.selectedAlgorithm = selectedAlgorithm; this.plan = plan;
                this.scoringState = scoringState; this.scoringOperationId = scoringOperationId;
                this.scoringRequestId = scoringRequestId; this.scoringError = scoringError;
                this.scoringStateVersion = scoringStateVersion;
                this.closedRootIds = closedRootIds;
                this.clarifications = new LinkedHashMap<>(clarifications);
                this.withdrawnQuestionIds = new LinkedHashSet<>(withdrawnQuestionIds);
                this.replacements = new LinkedHashMap<>(replacements);
                this.reviewSummary = reviewSummary; this.reviewRequestId = reviewRequestId;
                this.reviewOperationId = reviewOperationId;
                this.practiceFeedbacks = new LinkedHashMap<>(practiceFeedbacks);
                this.attempts = new LinkedHashMap<>(attempts);
            }
        }
    }

    /**
     * 开始一场面试。
     *
     * <p><b>刻意接收 {@code resume} 原文，而不是自己去查材料</b>：这样面试模块
     * 不依赖材料模块，简历从哪来由 Controller 决定。好处有两个：
     * <ul>
     *   <li>两个模块彻底解耦，各自可以独立测试和替换；</li>
     *   <li>不会出现「两套材料实现打架」——之前两边的 {@code /api/materials}
     *       路径撞在一起，会让 Spring 启动时抛 Ambiguous mapping，整个服务起不来。</li>
     * </ul>
     *
     * @param resume 简历原文，由材料模块抽取后传入。为空直接拒绝，不要拿空白去问模型。
     */
    public Session start(String owner, String materialId, String resume, String targetRole, int count, AiClient ai) {
        // Legacy clients may still send a count. Product sessions always use the fixed 12 + 1 flow.
        String role = targetRole == null ? "" : targetRole.strip();
        if (role.length() > 200) throw new IllegalArgumentException("目标岗位不能超过 200 字");
        if (resume == null || resume.isBlank())
            throw new InterviewException(400, "这份简历没有可用的文字内容，请换一份，或改用「粘贴文本」");
        Instant now = clock.instant();
        State state = new State(UUID.randomUUID().toString(), owner, materialId, resume, role, now,
                now.plusSeconds(Math.max(1, properties.getRetentionDays()) * 86400L));
        state.plan = InterviewPlan.legacyV3(
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, materialId == null ? "" : materialId),
                role);
        synchronized (sessions) {
            sessions.values().removeIf(s -> s.expires.isBefore(clock.instant()));
            if (sessions.size() >= 2000 || sessions.values().stream().filter(s -> s.owner.equals(owner)).count() >= 20)
                throw new InterviewException(429, "暂存面试数量已达上限，请稍后再试");
            sessions.put(state.id, state);
        }
        synchronized (state) {
            try {
                var opening = agent.prepareOpening(state.resume, role, ai);
                state.specialty = opening.specialty();
                if (state.specialty.kind() == InterviewSpecialty.Kind.ALGORITHM) state.selectedAlgorithm = Hot100Catalog.select();
                InterviewQuestion question = opening.question();
                String questionId = UUID.randomUUID().toString();
                state.questionContracts.put(questionId, question);
                state.contexts.put(questionId, new Context(Kind.MAIN, null, 1, null));
                state.turns.add(new Turn(questionId, question.text(), null));
                state.currentQuestionId = questionId;
                state.revision = 1;
                persist(state, null);
                return state.snapshot();
            } catch (RuntimeException e) {
                synchronized (sessions) { sessions.remove(state.id); }
                throw e;
            }
        }
    }

    /** Create or replay an idempotent empty opening session; the caller queues its model work. */
    public Session startAsync(String owner, String materialId, String resume, String targetRole, String requestId) {
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL,
                materialId == null ? "" : materialId);
        return startAsync(owner, new InterviewSourceResolver.ResolvedSource(source, "", resume, ""),
                targetRole, requestId);
    }

    /** Create/replay from one owner-resolved immutable source value. */
    public Session startAsync(String owner, InterviewSourceResolver.ResolvedSource source,
                              String targetRole, String requestId) {
        return startAsync(owner, source, null, targetRole, requestId);
    }

    /** Create/replay an experience-v1 session from a confirmed, immutable preview plan. */
    public Session startAsync(String owner, InterviewSourceResolver.ResolvedSource source,
                              InterviewPlan confirmedPlan, String requestId) {
        return startAsync(owner, source, confirmedPlan, requestId, false);
    }

    public Session startAsync(String owner, InterviewSourceResolver.ResolvedSource source,
                              InterviewPlan confirmedPlan, String requestId, boolean allowAlgorithmRepeat) {
        if (confirmedPlan == null) throw new IllegalArgumentException("已确认计划不能为空");
        return startAsync(owner, source, confirmedPlan, confirmedPlan.targetRole(), requestId, allowAlgorithmRepeat);
    }

    private Session startAsync(String owner, InterviewSourceResolver.ResolvedSource source,
                               InterviewPlan confirmedPlan, String targetRole, String requestId) {
        return startAsync(owner, source, confirmedPlan, targetRole, requestId, false);
    }

    private Session startAsync(String owner, InterviewSourceResolver.ResolvedSource source,
                               InterviewPlan confirmedPlan, String targetRole, String requestId, boolean allowAlgorithmRepeat) {
        String materialId = source.source().kind() == InterviewPlan.SourceKind.MATERIAL ? source.source().id() : null;
        String resume = source.resumeText();
        String role = targetRole == null ? "" : targetRole.strip();
        if (role.length() > 200) throw new IllegalArgumentException("目标岗位不能超过 200 字");
        if (resume == null || resume.isBlank())
            throw new InterviewException(400, "这份简历没有可用的文字内容，请换一份，或改用「粘贴文本」");
        if (requestId == null || requestId.isBlank() || requestId.length() > 120)
            throw new IllegalArgumentException("开始请求 ID 不能为空且不能超过 120 字");

        State state;
        synchronized (sessions) {
            state = sessions.values().stream().filter(s -> s.owner.equals(owner)
                    && requestId.equals(s.startRequestId)).findFirst().orElse(null);
            if (state == null && store != null) {
                var stored = store.findByStartRequestId(owner, requestId);
                if (stored.isPresent()) {
                    state = State.fromSnapshot(stored.get());
                    sessions.put(state.id, state);
                }
            }
            if (state != null) {
                if (state.expires.isBefore(clock.instant()))
                    throw new InterviewException(404, "面试不存在或已过期，请重新开始");
                boolean sameResolvedSource = state.plan != null
                        && Objects.equals(state.plan.source(), source.source())
                        && Objects.equals(state.plan.sourceLabel(), source.sourceLabel())
                        && Objects.equals(state.plan.sourceFingerprint(), source.sourceFingerprint());
                boolean sameLegacySource = state.plan == null && Objects.equals(state.materialId, materialId)
                        && state.resume.equals(resume);
                if ((!sameResolvedSource && !sameLegacySource) || !state.resume.equals(resume) || !state.role.equals(role))
                    throw new InterviewException(409, "开始请求 ID 已用于不同的简历或岗位");
                if (confirmedPlan != null && !confirmedPlan.equals(state.plan))
                    throw new InterviewException(409, "开始请求 ID 已用于不同版本的面试计划");
            } else {
                Instant now = clock.instant();
                state = new State(UUID.randomUUID().toString(), owner, materialId, resume, role,
                        now, now.plusSeconds(Math.max(1, properties.getRetentionDays()) * 86400L));
                state.startRequestId = requestId;
                if (confirmedPlan != null) {
                    if (!confirmedPlan.source().equals(source.source())
                            || !confirmedPlan.sourceFingerprint().equals(source.sourceFingerprint()))
                        throw new InterviewException(409, "简历来源已变化，请重新预览计划");
                    state.plan = confirmedPlan;
                    state.count = confirmedPlan.plannedCount();
                    if (confirmedPlan.specialty().kind() != InterviewPlan.SpecialtyKind.NONE) {
                        state.specialty = new InterviewSpecialty(
                                InterviewSpecialty.Kind.valueOf(confirmedPlan.specialty().kind().name()),
                                confirmedPlan.specialty().focus(), confirmedPlan.specialty().reason());
                        if (state.specialty.kind() == InterviewSpecialty.Kind.ALGORITHM)
                            state.selectedAlgorithm = HOT100.select(algorithmDifficulty(confirmedPlan.level()), usedAlgorithmIds(owner, confirmedPlan.source()), allowAlgorithmRepeat);
                    }
                } else {
                    var legacy = InterviewPlan.legacyV3(source.source(), role);
                    state.plan = new InterviewPlan(legacy.policyVersion(), legacy.mode(), legacy.mainCount(),
                            legacy.plannedCount(), legacy.followUpBudget(), legacy.maxFollowUpsPerMain(),
                            legacy.level(), legacy.careerStage(), legacy.targetRole(), legacy.jobDescription(),
                            legacy.specialty(), legacy.coverage(), source.source(), source.sourceLabel(),
                            source.sourceFingerprint(), legacy.practiceTargets());
                }
                sessions.values().removeIf(s -> s.expires.isBefore(now));
                InterviewStore.SessionCounts counts = store == null
                        ? new InterviewStore.SessionCounts(sessions.values().stream().filter(s -> s.owner.equals(owner)
                                && "ACTIVE".equals(s.status)).count(),
                                sessions.values().stream().filter(s -> s.owner.equals(owner)).count())
                        : store.counts(owner, now);
                if (sessions.size() >= 2000 || counts.active() >= 20 || counts.total() >= 100)
                    throw new InterviewException(429, "暂存面试数量已达上限，请稍后再试");
                sessions.put(state.id, state);
                try { persist(state, null); }
                catch (RuntimeException e) { sessions.remove(state.id); throw e; }
            }
        }
        // Claim after the empty snapshot is durable. Concurrent replays converge on this one operation.
        boolean needsOpening;
        synchronized (state) { needsOpening = state.generation == null && state.turns.isEmpty(); }
        if (needsOpening)
            claimGeneration(owner, state.id, requestId, InterviewSnapshot.GenerationKind.OPENING, state.revision);
        return get(owner, state.id);
    }
    public Session get(String owner, String id) {
        State state = find(owner, id);
        synchronized (state) { return state.snapshot(); }
    }

    /**
     * 仅保存一次回答，不调用模型、不生成下一题（计划 4.2 / T04）。
     *
     * <p>与 {@link #answer} 的耦合路径并存：本方法把「保存」从「生成」中拆出，
     * 让模型不可用时也能确认回答已落盘。下一题由生成认领（T05）与后台运行（T06）推进。
     */
    public Session saveAnswer(String owner, String sessionId, Submission submission) {
        String value = submission.answer() == null ? "" : submission.answer().strip();
        if (submission.questionId() == null || submission.questionId().isBlank())
            throw new IllegalArgumentException("题目 ID 不能为空");
        if (submission.submissionId() == null || submission.submissionId().isBlank())
            throw new IllegalArgumentException("提交 ID 不能为空");
        if (value.isEmpty() || value.length() > 5000) throw new IllegalArgumentException("回答须为 1–5000 字");
        State state = find(owner, sessionId);
        synchronized (state) {
            // 先按 questionId 定位题目（不限于当前题），以便幂等重放通过。
            Turn requested = state.turns.stream().filter(t -> t.questionId().equals(submission.questionId())).findFirst()
                    .orElseThrow(() -> new InterviewException(409, "题目已变更，请刷新面试记录"));
            if (requested.answer() != null) {
                // 已答：同 submissionId 同内容幂等返回；否则拒绝覆盖（计划 4.2 第 2 条）。
                String existing = state.submissionIds.get(submission.questionId());
                if (submission.submissionId().equals(existing) && requested.answer().equals(value))
                    return state.snapshot();
                throw new InterviewException(409, "这道题已经回答，不能重复修改");
            }
            if (!"ACTIVE".equals(state.status)) throw new InterviewException(409, "面试已结束，请开始新的面试");
            if (!submission.questionId().equals(state.currentQuestionId))
                throw new InterviewException(409, "请回答当前题目");
            int last = state.turns.size() - 1;
            if (!state.turns.get(last).questionId().equals(submission.questionId()))
                throw new InterviewException(409, "题目已变更，请刷新面试记录");
            var backup = state.backup();
            cancelClarification(state, clarificationRoot(state, submission.questionId()));
            cancelReplacement(state, submission.questionId());
            Turn answered = new Turn(requested.questionId(), requested.question(), value);
            state.turns.set(last, answered);
            state.submissionIds.put(submission.questionId(), submission.submissionId());
            var context = state.contexts.get(submission.questionId());
            if (context.kind().isSpecialty()) {
                state.status = "FINISHED";
                state.finishReason = "COMPLETED";
                state.currentQuestionId = null;
            } else {
                state.currentQuestionId = null; // 已保存，等待生成认领推进下一题
            }
            state.pending = null;
            state.revision++;
            persist(state, backup);
            return state.snapshot();
        }
    }

    /** Closes one root question group atomically; it never creates an assessment or scores an answer. */
    public Session closeRoot(String owner, String sessionId, String questionId, String requestId) {
        return closeRoot(owner, sessionId, questionId, requestId, null);
    }

    public Session closeRoot(String owner, String sessionId, String questionId, String requestId,
                             Long expectedRevision) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 120)
            throw new IllegalArgumentException("请求 ID 不能为空且不能超过 120 字");
        State state = find(owner, sessionId);
        synchronized (state) {
            String skipRequestKey = "skip-request:" + requestId;
            String previousQuestionId = state.submissionIds.get(skipRequestKey);
            if (previousQuestionId != null) {
                if (!previousQuestionId.equals(questionId)) throw new InterviewException(409, "跳题请求 ID 已用于另一道题");
                return state.snapshot();
            }
            if (!"ACTIVE".equals(state.status)) throw new InterviewException(409, "面试已结束，请开始新的面试");
            Context requested = state.contexts.get(questionId);
            if (requested == null) throw new InterviewException(404, "题目不存在");
            String rootId = requested.kind() == Kind.FOLLOW_UP ? requested.parentQuestionId() : questionId;
            Turn rootTurn = state.turns.stream().filter(turn -> turn.questionId().equals(rootId)).findFirst().orElse(null);
            if (rootTurn == null) throw new InterviewException(404, "题目不存在");
            boolean generationInFlight = state.generation != null && isActiveGeneration(state.generation.state());
            if (rootTurn.answer() != null && requested.kind() != Kind.FOLLOW_UP && !generationInFlight)
                throw new InterviewException(409, "已回答的主问题不能跳过");
            if (state.closedRootIds.contains(rootId)) return state.snapshot();
            if (expectedRevision != null && expectedRevision != state.revision)
                throw new InterviewException(409, "状态已变更，请刷新面试记录");
            String activeId = state.currentQuestionId;
            if (activeId != null) {
                Context active = state.contexts.get(activeId);
                String activeRoot = active.kind() == Kind.FOLLOW_UP ? active.parentQuestionId() : activeId;
                if (!rootId.equals(activeRoot)) throw new InterviewException(409, "只能关闭当前题组");
            }
            var backup = state.backup();
            state.closedRootIds.add(rootId);
            cancelClarification(state, rootId);
            cancelReplacement(state, rootId);
            state.submissionIds.put(skipRequestKey, questionId);
            if (activeId != null) state.currentQuestionId = null;
            if (state.generation != null && isActiveGeneration(state.generation.state()))
                state.generation = withState(state.generation, InterviewSnapshot.GenerationState.CANCELLED,
                        new InterviewFailure(InterviewFailure.Code.INTERRUPTED, false, "题组已关闭", null));
            boolean noSpecialtyRemaining = state.plan != null
                    && state.plan.specialty().kind() == InterviewPlan.SpecialtyKind.NONE
                    && state.progress().resolvedRoots() >= state.plan.mainCount();
            if (requested.kind().isSpecialty() || noSpecialtyRemaining) {
                state.status = "FINISHED";
                state.finishReason = "COMPLETED";
                state.currentQuestionId = null;
                state.pending = null;
            }
            state.revision++;
            persist(state, backup);
            return state.snapshot();
        }
    }

    public Session beginClarification(String owner, String sessionId, String questionId, String requestId,
                                      long expectedRevision) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 120)
            throw new IllegalArgumentException("请求 ID 不能为空且不能超过 120 字");
        State state = find(owner, sessionId);
        synchronized (state) {
            Context context = state.contexts.get(questionId);
            if (context == null) throw new InterviewException(404, "题目不存在");
            String rootId = context.kind() == Kind.FOLLOW_UP ? context.parentQuestionId() : questionId;
            var previous = state.clarifications.get(rootId);
            if (previous != null && previous.requestId().equals(requestId)) return state.snapshot();
            requireCurrentAction(state, questionId, expectedRevision);
            if (previous != null && previous.status() != InterviewSnapshot.ClarificationStatus.FAILED
                    && previous.status() != InterviewSnapshot.ClarificationStatus.CANCELLED)
                throw new InterviewException(409, "这组题目已经请求过澄清");
            var backup = state.backup();
            state.clarifications.put(rootId, new InterviewSnapshot.Clarification(requestId,
                    InterviewSnapshot.ClarificationStatus.RUNNING, null, null));
            state.revision++; state.stateVersion++;
            persist(state, backup);
            return state.snapshot();
        }
    }

    public boolean completeClarification(String owner, String sessionId, String questionId, String requestId, String text) {
        if (text == null || text.isBlank() || text.length() > 2000) return false;
        State state = find(owner, sessionId);
        synchronized (state) {
            String rootId = clarificationRoot(state, questionId);
            var operation = state.clarifications.get(rootId);
            if (operation == null || !operation.requestId().equals(requestId)
                    || operation.status() != InterviewSnapshot.ClarificationStatus.RUNNING) return false;
            if (!"ACTIVE".equals(state.status) || !questionId.equals(state.currentQuestionId)
                    || state.closedRootIds.contains(rootId)) {
                cancelClarification(state, rootId);
                return false;
            }
            var backup = state.backup();
            state.clarifications.put(rootId, new InterviewSnapshot.Clarification(requestId,
                    InterviewSnapshot.ClarificationStatus.COMPLETE, text.strip(), null));
            state.revision++; state.stateVersion++;
            persist(state, backup);
            return true;
        }
    }

    public boolean failClarification(String owner, String sessionId, String questionId, String requestId, String error) {
        State state = find(owner, sessionId);
        synchronized (state) {
            String rootId = clarificationRoot(state, questionId);
            var operation = state.clarifications.get(rootId);
            if (operation == null || !operation.requestId().equals(requestId)
                    || operation.status() != InterviewSnapshot.ClarificationStatus.RUNNING) return false;
            if (!"ACTIVE".equals(state.status) || !questionId.equals(state.currentQuestionId)
                    || state.closedRootIds.contains(rootId)) {
                cancelClarification(state, rootId);
                return false;
            }
            var backup = state.backup();
            state.clarifications.put(rootId, new InterviewSnapshot.Clarification(requestId,
                    InterviewSnapshot.ClarificationStatus.FAILED, null,
                    error == null || error.isBlank() ? "题目解释暂不可用，请重试" : error));
            state.revision++; state.stateVersion++;
            persist(state, backup);
            return true;
        }
    }

    public Session beginReplacement(String owner, String sessionId, String questionId, String requestId,
                                    long expectedRevision, String reason) {
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > 300) throw new IllegalArgumentException("换题原因须为 1–300 字");
        if (requestId == null || requestId.isBlank() || requestId.length() > 120)
            throw new IllegalArgumentException("请求 ID 不能为空且不能超过 120 字");
        State state = find(owner, sessionId);
        synchronized (state) {
            var previous = state.replacements.get(questionId);
            if (previous != null && previous.requestId().equals(requestId)) return state.snapshot();
            if (!"ACTIVE".equals(state.status)) throw new InterviewException(409, "面试已结束，不能换题");
            if (expectedRevision != state.revision) throw new InterviewException(409, "状态已变更，请刷新面试记录");
            Context context = state.contexts.get(questionId);
            if (context == null) throw new InterviewException(404, "题目不存在");
            if (context.kind() != Kind.MAIN) throw new InterviewException(409, "只能替换未回答的主问题，专项题和追问不能换题");
            if (!questionId.equals(state.currentQuestionId)) throw new InterviewException(409, "只能替换当前未回答的问题");
            Turn source = state.turns.stream().filter(turn -> turn.questionId().equals(questionId)).findFirst().orElse(null);
            if (source == null || source.answer() != null || state.closedRootIds.contains(questionId))
                throw new InterviewException(409, "已回答或已跳过的题目不能换题");
            if (state.replacements.values().stream().anyMatch(r -> questionId.equals(r.replacementQuestionId())))
                throw new InterviewException(409, "这道主问题已经使用过换题机会");
            if (previous != null && previous.status() == InterviewSnapshot.ReplacementStatus.RUNNING)
                throw new InterviewException(409, "换题仍在处理中");
            if (previous != null && previous.status() == InterviewSnapshot.ReplacementStatus.COMPLETE)
                throw new InterviewException(409, "这道主问题已经使用过换题机会");
            var backup = state.backup();
            state.replacements.put(questionId, new InterviewSnapshot.Replacement(requestId,
                    InterviewSnapshot.ReplacementStatus.RUNNING, why, null, null));
            state.revision++; state.stateVersion++;
            persist(state, backup);
            return state.snapshot();
        }
    }

    public boolean completeReplacement(String owner, String sessionId, String sourceQuestionId, String requestId,
                                       InterviewQuestion replacementQuestion) {
        if (replacementQuestion == null) return false;
        State state = find(owner, sessionId);
        synchronized (state) {
            var operation = state.replacements.get(sourceQuestionId);
            if (operation == null || !operation.requestId().equals(requestId)
                    || operation.status() != InterviewSnapshot.ReplacementStatus.RUNNING) return false;
            if (!"ACTIVE".equals(state.status) || !sourceQuestionId.equals(state.currentQuestionId)
                    || state.closedRootIds.contains(sourceQuestionId)) {
                cancelReplacement(state, sourceQuestionId);
                return false;
            }
            var original = state.questionContracts.get(sourceQuestionId);
            if (original == null || !original.competency().equals(replacementQuestion.competency())
                    || original.importance() != replacementQuestion.importance()
                    || original.difficulty() != replacementQuestion.difficulty())
                throw new InterviewException(502, "替换题未保持原考察方向与难度，请重试");
            var backup = state.backup();
            String replacementId = UUID.randomUUID().toString();
            Context sourceContext = state.contexts.get(sourceQuestionId);
            state.withdrawnQuestionIds.add(sourceQuestionId);
            cancelClarification(state, sourceQuestionId);
            state.questionContracts.put(replacementId, replacementQuestion);
            state.contexts.put(replacementId, new Context(Kind.MAIN, null, sourceContext.mainQuestionIndex(), null));
            state.turns.add(new Turn(replacementId, replacementQuestion.text(), null));
            state.currentQuestionId = replacementId;
            state.replacements.put(sourceQuestionId, new InterviewSnapshot.Replacement(requestId,
                    InterviewSnapshot.ReplacementStatus.COMPLETE, operation.reason(), replacementId, null));
            state.revision++; state.stateVersion++;
            persist(state, backup);
            return true;
        }
    }

    public boolean failReplacement(String owner, String sessionId, String sourceQuestionId, String requestId, String error) {
        State state = find(owner, sessionId);
        synchronized (state) {
            var operation = state.replacements.get(sourceQuestionId);
            if (operation == null || !operation.requestId().equals(requestId)
                    || operation.status() != InterviewSnapshot.ReplacementStatus.RUNNING) return false;
            if (!"ACTIVE".equals(state.status) || !sourceQuestionId.equals(state.currentQuestionId)
                    || state.closedRootIds.contains(sourceQuestionId)) {
                cancelReplacement(state, sourceQuestionId);
                return false;
            }
            var backup = state.backup();
            state.replacements.put(sourceQuestionId, new InterviewSnapshot.Replacement(requestId,
                    InterviewSnapshot.ReplacementStatus.FAILED, operation.reason(), null,
                    error == null || error.isBlank() ? "换题暂不可用，请重试" : error));
            state.revision++; state.stateVersion++;
            persist(state, backup);
            return true;
        }
    }

    public ReplacementContext replacementContext(String owner, String sessionId, String questionId) {
        State state = find(owner, sessionId);
        synchronized (state) {
            Context context = state.contexts.get(questionId);
            if (context == null || context.kind() != Kind.MAIN || !questionId.equals(state.currentQuestionId))
                throw new InterviewException(409, "只能替换当前主问题");
            return new ReplacementContext(state.resume, state.role, state.questionContracts.get(questionId));
        }
    }

    private static void cancelReplacement(State state, String questionId) {
        var operation = state.replacements.get(questionId);
        if (operation == null) {
            var entry = state.replacements.entrySet().stream()
                    .filter(item -> questionId.equals(item.getValue().replacementQuestionId())).findFirst().orElse(null);
            if (entry != null) operation = entry.getValue();
        }
        if (operation != null && operation.status() == InterviewSnapshot.ReplacementStatus.RUNNING) {
            for (var entry : new ArrayList<>(state.replacements.entrySet()))
                if (entry.getValue() == operation || entry.getValue().equals(operation))
                    state.replacements.put(entry.getKey(), new InterviewSnapshot.Replacement(operation.requestId(),
                            InterviewSnapshot.ReplacementStatus.CANCELLED, operation.reason(), null, null));
        }
    }

    private static void requireCurrentAction(State state, String questionId, long expectedRevision) {
        if (!"ACTIVE".equals(state.status)) throw new InterviewException(409, "面试已结束");
        if (expectedRevision != state.revision) throw new InterviewException(409, "状态已变更，请刷新面试记录");
        if (questionId == null || !questionId.equals(state.currentQuestionId))
            throw new InterviewException(409, "只能澄清当前题目");
    }

    private static String clarificationRoot(State state, String questionId) {
        Context context = state.contexts.get(questionId);
        return context == null ? questionId : context.kind() == Kind.FOLLOW_UP ? context.parentQuestionId() : questionId;
    }

    private static void cancelClarification(State state, String rootId) {
        var operation = state.clarifications.get(rootId);
        if (operation != null && operation.status() == InterviewSnapshot.ClarificationStatus.RUNNING) {
            state.clarifications.put(rootId, new InterviewSnapshot.Clarification(operation.requestId(),
                    InterviewSnapshot.ClarificationStatus.CANCELLED, null, null));
        }
    }

    public Session answer(String owner, String id, String questionId, String answer, AiClient ai) {
        String value = answer == null ? "" : answer.strip();
        if (value.isEmpty() || value.length() > 5000) throw new IllegalArgumentException("回答须为 1–5000 字");
        State state = find(owner, id);
        synchronized (state) {
            Turn requested = state.turns.stream().filter(t -> t.questionId().equals(questionId)).findFirst()
                    .orElseThrow(() -> new InterviewException(409, "题目已变更，请刷新面试记录"));
            if (requested.answer() != null) {
                if (requested.answer().equals(value)) return state.snapshot();
                throw new InterviewException(409, "这道题已经回答，不能重复修改");
            }
            if (!state.status.equals("ACTIVE")) throw new InterviewException(409, "面试已结束，请开始新的面试");
            int last = state.turns.size()-1;
            if (!state.turns.get(last).questionId().equals(questionId)) throw new InterviewException(409, "请回答当前题目");
            if (state.pending != null && !state.pending.equals(value))
                throw new InterviewException(409, "已有待重试回答，请刷新记录并重试原回答");
            var backup = state.backup();
            state.pending = value;
            Turn answered = new Turn(requested.questionId(), requested.question(), value);
            var context = state.contexts.get(questionId);
            if (context.kind().isSpecialty()) {
                state.turns.set(last, answered); state.pending = null; state.status = "FINISHED";
                state.finishReason = "COMPLETED"; state.currentQuestionId = null;
                state.revision++; persist(state, backup);
                return state.snapshot();
            } else {
                List<Turn> history = new ArrayList<>(state.turns); history.set(last, answered);
                String parentId = context.kind() == Kind.FOLLOW_UP ? context.parentQuestionId() : questionId;
                long followUpCount = state.contexts.values().stream().filter(c -> parentId.equals(c.parentQuestionId())).count();
                long sessionFollowUpCount = state.contexts.values().stream().filter(c -> c.kind() == Kind.FOLLOW_UP).count();
                boolean allowFollowUp = state.plan.allowsFollowUp(sessionFollowUpCount, followUpCount);
                int nextIndex = context.mainQuestionIndex() + 1;
                int mainCount = "experience-v1".equals(state.plan.policyVersion()) ? state.plan.mainCount() : MAIN_COUNT;
                boolean hasSpecialty = state.plan.plannedCount() > mainCount;
                InterviewAgent.Proposal proposal = allowFollowUp
                        ? prepareNext(state.resume, state.role, history, mainCount, nextIndex, true, state.plan, ai)
                        : nextIndex <= mainCount ? prepareNext(state.resume, state.role, history, mainCount, nextIndex, false, state.plan, ai)
                        : new InterviewAgent.Proposal(null, false);
                if (proposal.followUp() && !allowFollowUp)
                    throw new InterviewException(502, "模型生成的追问超出已确认计划",
                            new InterviewFailure(InterviewFailure.Code.INVALID_OUTPUT, false,
                                    "模型生成的追问超出已确认计划", null));
                if (!proposal.followUp() && nextIndex <= mainCount) validateCoverage(state.plan, proposal);
                Context nextContext;
                InterviewQuestion question;
                if (proposal.followUp()) {
                    var parent = state.questionContracts.get(parentId);
                    // Freeze the parent's rubric; model-provided follow-up rubrics and weights are discarded.
                    question = new InterviewQuestion(proposal.question().text(), parent.competency(), parent.importance(),
                            parent.difficulty(), parent.weightReason(), parent.criteria(), parent.rubricVersion());
                    nextContext = new Context(Kind.FOLLOW_UP, parentId, context.mainQuestionIndex(), null);
                } else if (nextIndex <= mainCount) {
                    question = proposal.question();
                    nextContext = new Context(Kind.MAIN, null, nextIndex, null);
                } else if (hasSpecialty) {
                    if (state.specialty.kind() == InterviewSpecialty.Kind.ALGORITHM) {
                        question = agent.prepareAlgorithm(state.selectedAlgorithm, ai);
                        nextContext = new Context(Kind.ALGORITHM, null, mainCount + 1, state.selectedAlgorithm.overview());
                    } else {
                        question = agent.prepareCase(state.specialty, state.role, ai);
                        nextContext = new Context(Kind.CASE_STUDY, null, mainCount + 1, null);
                    }
                } else {
                    state.turns.set(last, answered); state.pending = null; state.status = "FINISHED";
                    state.finishReason = "COMPLETED"; state.currentQuestionId = null;
                    state.revision++; persist(state, backup);
                    return state.snapshot();
                }
                String nextId = UUID.randomUUID().toString();
                state.turns.set(last, answered);
                state.questionContracts.put(nextId, question);
                state.contexts.put(nextId, nextContext);
                state.turns.add(new Turn(nextId, question.text(), null));
                state.currentQuestionId = nextId;
                state.pending = null;
                state.revision++;
                persist(state, backup);
                return state.snapshot();
            }
        }
    }
    public Session finish(String owner, String id) {
        return finish(owner, id, null, null);
    }

    /** Atomically save the optional final answer and finish without starting another generation. */
    public Session finish(String owner, String id, String requestId, Submission submission) {
        State state = find(owner, id);
        synchronized (state) {
            if ("FINISHED".equals(state.status)) {
                if (submission != null) {
                    Turn existing = state.turns.stream()
                            .filter(t -> t.questionId().equals(submission.questionId())).findFirst()
                            .orElseThrow(() -> new InterviewException(409, "题目已变更，请刷新面试记录"));
                    String value = submission.answer() == null ? "" : submission.answer().strip();
                    if (!Objects.equals(existing.answer(), value)
                            || !Objects.equals(state.submissionIds.get(submission.questionId()), submission.submissionId()))
                        throw new InterviewException(409, "面试已结束，不能修改回答");
                }
                return state.snapshot();
            }
            var backup = state.backup();
            for (String rootId : new ArrayList<>(state.clarifications.keySet())) cancelClarification(state, rootId);
            for (String rootId : new ArrayList<>(state.replacements.keySet())) cancelReplacement(state, rootId);
            if (submission != null) {
                String value = submission.answer() == null ? "" : submission.answer().strip();
                if (submission.questionId() == null || submission.questionId().isBlank()
                        || submission.submissionId() == null || submission.submissionId().isBlank())
                    throw new IllegalArgumentException("题目 ID 和提交 ID 不能为空");
                if (value.isEmpty() || value.length() > 5000) throw new IllegalArgumentException("回答须为 1–5000 字");
                int index = -1;
                for (int i = 0; i < state.turns.size(); i++)
                    if (state.turns.get(i).questionId().equals(submission.questionId())) { index = i; break; }
                if (index < 0) throw new InterviewException(409, "题目已变更，请刷新面试记录");
                Turn current = state.turns.get(index);
                if (current.answer() != null) {
                    if (!current.answer().equals(value)
                            || !Objects.equals(state.submissionIds.get(submission.questionId()), submission.submissionId()))
                        throw new InterviewException(409, "这道题已经回答，不能重复修改");
                } else {
                    if (!Objects.equals(state.currentQuestionId, submission.questionId()))
                        throw new InterviewException(409, "请回答当前题目");
                    state.turns.set(index, new Turn(current.questionId(), current.question(), value));
                    state.submissionIds.put(submission.questionId(), submission.submissionId());
                    state.pending = null;
                }
            }
            if (state.pending != null && !state.turns.isEmpty()) {
                int last = state.turns.size()-1; Turn current = state.turns.get(last);
                state.turns.set(last, new Turn(current.questionId(), current.question(), state.pending));
                state.submissionIds.putIfAbsent(current.questionId(), "finish-" + state.revision);
                state.pending = null;
            }
            if (!"FINISHED".equals(state.status)) {
                state.status = "FINISHED";
                if (state.finishReason == null) state.finishReason = "USER_ENDED";
                state.currentQuestionId = null;
                if (state.generation != null && isActiveGeneration(state.generation.state()))
                    state.generation = withState(state.generation, InterviewSnapshot.GenerationState.CANCELLED,
                            new InterviewFailure(InterviewFailure.Code.INTERRUPTED, false, "面试已结束", null));
                state.revision++;
                persist(state, backup);
            }
            return state.snapshot();
        }
    }

    /**
     * 一次生成操作成功后的提交内容（计划 4.1 / T05）。runner 用模型生成后填入，
     * coordinator 据此把题目写入会话。{@code specialty}/{@code selectedAlgorithm} 仅 OPENING 提供。
     */
    public record GenerationResult(InterviewQuestion question, Kind kind, String parentQuestionId,
                                   int mainQuestionIndex, Hot100Catalog.Algorithm algorithm,
                                   InterviewSpecialty specialty, Hot100Catalog.Problem selectedAlgorithm,
                                   boolean completed) {
        public GenerationResult(InterviewQuestion question, Kind kind, String parentQuestionId,
                                int mainQuestionIndex, Hot100Catalog.Algorithm algorithm,
                                InterviewSpecialty specialty, Hot100Catalog.Problem selectedAlgorithm) {
            this(question, kind, parentQuestionId, mainQuestionIndex, algorithm, specialty, selectedAlgorithm, false);
        }
        public GenerationResult {
            if (!completed) {
                Objects.requireNonNull(question, "question");
                Objects.requireNonNull(kind, "kind");
            } else if (question != null || kind != null) {
                throw new IllegalArgumentException("完成结果不能携带下一题");
            }
        }
        public static GenerationResult completion() { return new GenerationResult(null, null, null, 0, null, null, null, true); }
    }

    /**
     * 认领一次生成操作（计划 4.2 第 4/6 条 / T05）。
     *
     * <p>活跃操作存在时直接加入现有操作、不排第二个任务；否则校验 revision 与前置条件后
     * 新建 QUEUED 操作并持久化。返回 {@code generationId} 供回写校验。
     */
    public String claimGeneration(String owner, String id, String requestId,
                                  InterviewSnapshot.GenerationKind kind, long expectedRevision) {
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("请求 ID 不能为空");
        if (kind == null) throw new IllegalArgumentException("生成类型不能为空");
        State state = find(owner, id);
        synchronized (state) {
            if (!"ACTIVE".equals(state.status)) throw new InterviewException(409, "面试已结束，请开始新的面试");
            if (state.generation != null && isActiveGeneration(state.generation.state()))
                return state.generation.generationId();
            if (expectedRevision != state.revision)
                throw new InterviewException(409, "状态已变更，请刷新面试记录");
            if (kind == InterviewSnapshot.GenerationKind.OPENING) {
                if (!state.turns.isEmpty()) throw new InterviewException(409, "首题已生成，不能再认领首题操作");
            } else {
                if (state.currentQuestionId != null) throw new InterviewException(409, "当前题尚未回答");
                if (state.turns.isEmpty())
                    throw new InterviewException(409, "没有可推进的已保存回答");
                Turn last = state.turns.get(state.turns.size() - 1);
                Context context = state.contexts.get(last.questionId());
                String rootId = context.kind() == Kind.FOLLOW_UP ? context.parentQuestionId() : last.questionId();
                if (last.answer() == null && !state.closedRootIds.contains(rootId))
                    throw new InterviewException(409, "没有可推进的已保存回答");
            }
            var backup = state.backup();
            String generationId = UUID.randomUUID().toString();
            Instant now = clock.instant();
            var stage = kind == InterviewSnapshot.GenerationKind.OPENING
                    ? InterviewSnapshot.GenerationStage.OPENING
                    : InterviewSnapshot.GenerationStage.NEXT_DECISION;
            state.generation = new InterviewSnapshot.Generation(generationId, requestId, kind, stage,
                    InterviewSnapshot.GenerationState.QUEUED, 0, 0, properties.getMaxRetries(),
                    now, now.plusSeconds(properties.getGenerationDeadlineSeconds()), null, null);
            persist(state, backup);
            return generationId;
        }
    }

    /** 成功回写：校验操作仍活跃且 revision 未变后追加一题，操作置 SUCCEEDED（计划 4.2 第 5/6 条）。 */
    public Session commitGenerationSuccess(String owner, String id, String generationId,
                                           long expectedRevision, GenerationResult result) {
        if (result == null) throw new IllegalArgumentException("生成结果不能为空");
        State state = find(owner, id);
        synchronized (state) {
            if (!"ACTIVE".equals(state.status)) throw new InterviewException(409, "面试已结束，请开始新的面试");
            verifyActiveGeneration(state, generationId, expectedRevision);
            var backup = state.backup();
            if (result.completed()) {
                state.status = "FINISHED";
                state.finishReason = "COMPLETED";
                state.currentQuestionId = null;
            } else {
                String questionId = UUID.randomUUID().toString();
                state.questionContracts.put(questionId, result.question());
                state.contexts.put(questionId, new Context(result.kind(), result.parentQuestionId(),
                        result.mainQuestionIndex(), result.algorithm()));
                state.turns.add(new Turn(questionId, result.question().text(), null));
                state.currentQuestionId = questionId;
            }
            if (result.specialty() != null) state.specialty = result.specialty();
            if (result.selectedAlgorithm() != null) state.selectedAlgorithm = result.selectedAlgorithm();
            var generation = state.generation;
            state.generation = new InterviewSnapshot.Generation(generation.generationId(), generation.requestId(),
                    generation.kind(), generation.stage(), InterviewSnapshot.GenerationState.SUCCEEDED,
                    generation.attempt(), generation.retryCount(), generation.maxRetries(),
                    generation.startedAt(), generation.deadlineAt(), null, null);
            state.revision++;
            persist(state, backup);
            return state.snapshot();
        }
    }

    /** 失败回写：操作置 FAILED 并记录失败，不修改题目或 revision（计划 4.2 第 7 条）。 */
    public Session commitGenerationFailure(String owner, String id, String generationId,
                                           long expectedRevision, InterviewFailure failure) {
        State state = find(owner, id);
        synchronized (state) {
            if (!"ACTIVE".equals(state.status)) throw new InterviewException(409, "面试已结束，请开始新的面试");
            verifyActiveGeneration(state, generationId, expectedRevision);
            var backup = state.backup();
            state.generation = withState(state.generation, InterviewSnapshot.GenerationState.FAILED, failure);
            persist(state, backup);
            return state.snapshot();
        }
    }

    /** 在短锁内冻结模型输入并标记 RUNNING；随后所有模型 I/O 均在锁外执行。 */
    public GenerationResult generate(InterviewGenerationCoordinator.GenerationClaim claim, AiClient ai) {
        State state = find(claim.owner(), claim.sessionId());
        String resume, role;
        List<Turn> history;
        Context context = null;
        InterviewQuestion parentContract = null;
        long followUpCount = 0;
        long sessionFollowUpCount = 0;
        boolean rootClosed = false;
        InterviewSpecialty specialty;
        InterviewPlan plan;
        Hot100Catalog.Problem selected;
        synchronized (state) {
            verifyActiveGeneration(state, claim.generationId(), claim.expectedRevision());
            var backup = state.backup();
            state.generation = withState(state.generation, InterviewSnapshot.GenerationState.RUNNING, null);
            state.stateVersion++;
            persist(state, backup);
            resume = state.resume;
            role = state.role;
            history = state.turns.stream().filter(turn -> !state.withdrawnQuestionIds.contains(turn.questionId())).toList();
            specialty = state.specialty;
            plan = state.plan;
            selected = state.selectedAlgorithm;
            if (claim.kind() == InterviewSnapshot.GenerationKind.NEXT) {
                Turn last = history.get(history.size() - 1);
                context = state.contexts.get(last.questionId());
                String parentId = context.kind() == Kind.FOLLOW_UP ? context.parentQuestionId() : last.questionId();
                parentContract = state.questionContracts.get(parentId);
                followUpCount = state.contexts.values().stream().filter(c -> parentId.equals(c.parentQuestionId())).count();
                sessionFollowUpCount = state.contexts.values().stream().filter(c -> c.kind() == Kind.FOLLOW_UP).count();
                rootClosed = state.closedRootIds.contains(parentId);
            }
        }
        if (claim.kind() == InterviewSnapshot.GenerationKind.OPENING) {
            if (plan != null && "experience-v1".equals(plan.policyVersion())) {
                InterviewQuestion question = withRetries(claim, InterviewSnapshot.GenerationStage.OPENING, ai,
                        timeout -> agent.preparePlannedOpening(resume, plan, ai, timeout));
                Hot100Catalog.Problem algorithm = plan.specialty().kind() == InterviewPlan.SpecialtyKind.ALGORITHM
                        ? selected : null;
                return new GenerationResult(question, Kind.MAIN, null, 1, null, specialty, algorithm);
            }
            var opening = withRetries(claim, InterviewSnapshot.GenerationStage.OPENING, ai,
                    timeout -> agent.prepareOpening(resume, role, ai, timeout));
            Hot100Catalog.Problem algorithm = opening.specialty().kind() == InterviewSpecialty.Kind.ALGORITHM
                    ? Hot100Catalog.select() : null;
            return new GenerationResult(opening.question(), Kind.MAIN, null, 1,
                    algorithm == null ? null : algorithm.overview(), opening.specialty(), algorithm);
        }
        if (context == null || history.isEmpty()) throw new InterviewException(409, "没有可推进的已保存回答");
        Turn last = history.get(history.size() - 1);
        Turn answered = new Turn(last.questionId(), last.question(), last.answer());
        int nextIndex = context.mainQuestionIndex() + 1;
        boolean planned = "experience-v1".equals(plan.policyVersion());
        int mainCount = planned ? plan.mainCount() : MAIN_COUNT;
        boolean hasSpecialty = planned
                ? plan.plannedCount() > plan.mainCount()
                : QUESTION_COUNT > MAIN_COUNT;
        boolean allowFollowUp = !rootClosed && plan.allowsFollowUp(sessionFollowUpCount, followUpCount);
        InterviewAgent.Proposal proposal = allowFollowUp
                ? withRetries(claim, InterviewSnapshot.GenerationStage.NEXT_DECISION, ai,
                    timeout -> prepareNext(resume, role, history, mainCount, nextIndex, true, plan, ai, timeout))
                : nextIndex <= mainCount
                    ? withRetries(claim, InterviewSnapshot.GenerationStage.NEXT_DECISION, ai,
                        timeout -> prepareNext(resume, role, history, mainCount, nextIndex, false, plan, ai, timeout))
                    : new InterviewAgent.Proposal(null, false);
        if (proposal.followUp() && !allowFollowUp)
            throw new InterviewException(502, "模型生成的追问超出已确认计划",
                    new InterviewFailure(InterviewFailure.Code.INVALID_OUTPUT, false,
                            "模型生成的追问超出已确认计划", null));
        if (!proposal.followUp() && nextIndex <= mainCount) validateCoverage(plan, proposal);
        if (proposal.followUp()) {
            InterviewQuestion q = proposal.question();
            InterviewQuestion frozen = new InterviewQuestion(q.text(), parentContract.competency(), parentContract.importance(),
                    parentContract.difficulty(), parentContract.weightReason(), parentContract.criteria(), parentContract.rubricVersion());
            String parentId = context.kind() == Kind.FOLLOW_UP ? context.parentQuestionId() : last.questionId();
            return new GenerationResult(frozen, Kind.FOLLOW_UP, parentId, context.mainQuestionIndex(), null, null, null);
        }
        if (nextIndex <= mainCount)
            return new GenerationResult(proposal.question(), Kind.MAIN, null, nextIndex, null, null, null);
        if (!hasSpecialty) return GenerationResult.completion();
        if (nextIndex == mainCount + 1) {
            InterviewQuestion q = specialty.kind() == InterviewSpecialty.Kind.ALGORITHM
                    ? withRetries(claim, InterviewSnapshot.GenerationStage.SPECIALTY, ai,
                        timeout -> agent.prepareAlgorithm(selected, ai, timeout))
                    : withRetries(claim, InterviewSnapshot.GenerationStage.SPECIALTY, ai,
                        timeout -> agent.prepareCase(specialty, role, plan.level(), ai, timeout));
            return new GenerationResult(q,
                    specialty.kind() == InterviewSpecialty.Kind.ALGORITHM ? Kind.ALGORITHM : Kind.CASE_STUDY,
                    null, mainCount + 1, specialty.kind() == InterviewSpecialty.Kind.ALGORITHM ? selected.overview() : null,
                    null, null);
        }
        throw new InterviewException(502, "生成题目数量超出已确认计划",
                new InterviewFailure(InterviewFailure.Code.INVALID_OUTPUT, false, "生成题目数量超出已确认计划", null));
    }

    private Set<String> usedAlgorithmIds(String owner, InterviewPlan.ResumeSource source) {
        Set<String> used = new HashSet<>();
        if (store != null) store.findBySource(owner, source).stream()
                .map(InterviewSnapshot::selectedAlgorithm).filter(Objects::nonNull)
                .map(Hot100Catalog.Problem::id).forEach(used::add);
        synchronized (sessions) {
            sessions.values().stream().filter(state -> state.owner.equals(owner) && state.plan != null
                            && source.equals(state.plan.source()) && state.selectedAlgorithm != null)
                    .map(state -> state.selectedAlgorithm.id()).forEach(used::add);
        }
        return Set.copyOf(used);
    }

    static String algorithmDifficulty(InterviewPlan.Level level) {
        if (level == null) throw new IllegalArgumentException("计划难度不能为空");
        return switch (level) {
            case FOUNDATION -> "EASY";
            case STANDARD -> "MEDIUM";
            case ADVANCED -> "HARD";
        };
    }

    @FunctionalInterface
    private interface TimedStage<T> { T run(int timeoutSeconds); }

    private InterviewAgent.Proposal prepareNext(String resume, String role, List<Turn> history, int total,
            int nextIndex, boolean allowFollowUp, InterviewPlan plan, AiClient ai) {
        if ("experience-v1".equals(plan.policyVersion()))
            return agent.preparePlannedNext(resume, role, history, total, nextIndex, allowFollowUp, plan, ai);
        return agent.prepareNext(resume, role, history, total, nextIndex, allowFollowUp, ai);
    }

    private InterviewAgent.Proposal prepareNext(String resume, String role, List<Turn> history, int total,
            int nextIndex, boolean allowFollowUp, InterviewPlan plan, AiClient ai, int timeoutSeconds) {
        if ("experience-v1".equals(plan.policyVersion()))
            return agent.preparePlannedNext(resume, role, history, total, nextIndex, allowFollowUp, plan, ai, timeoutSeconds);
        return agent.prepareNext(resume, role, history, total, nextIndex, allowFollowUp, ai, timeoutSeconds);
    }

    private static void validateCoverage(InterviewPlan plan, InterviewAgent.Proposal proposal) {
        if (plan != null && "experience-v1".equals(plan.policyVersion())
                && (proposal.coverageId() == null
                    || plan.coverage().stream().noneMatch(c -> c.id().equals(proposal.coverageId()))))
            throw new InterviewException(502, "模型选择了未确认的考察方向",
                    new InterviewFailure(InterviewFailure.Code.INVALID_OUTPUT, false,
                            "模型选择了未确认的考察方向", null));
    }

    /** Retry one model stage in place; completed earlier stages are retained in this call's stack. */
    private <T> T withRetries(InterviewGenerationCoordinator.GenerationClaim claim,
                              InterviewSnapshot.GenerationStage stage, AiClient ai, TimedStage<T> operation) {
        var policy = new InterviewRetryPolicy(properties.getMaxRetries(), properties.getAttemptTimeoutSeconds(),
                properties.getRetryDelaysMs());
        while (true) {
            int timeoutSeconds = beginGenerationAttempt(claim, stage);
            long startedAtNanos = System.nanoTime();
            try {
                return operation.run(timeoutSeconds);
            } catch (InterviewException error) {
                InterviewFailure failure = error.failure() != null ? error.failure()
                        : new InterviewFailure(error.status() >= 500
                                ? InterviewFailure.Code.UPSTREAM_UNAVAILABLE : InterviewFailure.Code.UNKNOWN,
                                error.status() >= 500, error.getMessage(), null);
                var current = get(claim.owner(), claim.sessionId()).generation();
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
                LOG.warn("面试出题失败 requestId={} sessionId={} generationId={} stage={} model={} attempt={} elapsedMs={} failureCode={}",
                        claim.requestId(), claim.sessionId(), claim.generationId(), stage,
                        ai == null ? "?" : ai.name(), current.attempt(), elapsedMs, failure.code());
                var decision = policy.decide(failure, current.attempt(), current.retryCount(),
                        current.deadlineAt(), clock.instant());
                if (!decision.retry()) throw new InterviewException(error.status(), failure.message(), failure);
                long delay = decision.delayMs();
                Instant nextRetryAt = clock.instant().plusMillis(delay);
                setGenerationRetryWait(claim, failure, nextRetryAt);
                try {
                    if (delay > 0) retrySleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterviewException(503, "模型生成等待已中断",
                            new InterviewFailure(InterviewFailure.Code.INTERRUPTED, false,
                                    "模型生成等待已中断", null));
                }
            }
        }
    }

    /** Records every real upstream request and returns a timeout bounded by the remaining total budget. */
    private int beginGenerationAttempt(InterviewGenerationCoordinator.GenerationClaim claim,
                                      InterviewSnapshot.GenerationStage stage) {
        State state = find(claim.owner(), claim.sessionId());
        synchronized (state) {
            verifyActiveGeneration(state, claim.generationId(), claim.expectedRevision());
            long remainingSeconds = java.time.Duration.between(clock.instant(), state.generation.deadlineAt()).getSeconds();
            if (remainingSeconds < 5)
                throw new InterviewException(504, "模型生成总时限已用完，请重新尝试",
                        new InterviewFailure(InterviewFailure.Code.TIMEOUT, false, "模型生成总时限已用完", null));
            var backup = state.backup();
            var generation = state.generation;
            state.generation = new InterviewSnapshot.Generation(generation.generationId(), generation.requestId(),
                    generation.kind(), stage, InterviewSnapshot.GenerationState.RUNNING,
                    generation.attempt() + 1, generation.retryCount(), generation.maxRetries(),
                    generation.startedAt(), generation.deadlineAt(), null, generation.failure());
            persist(state, backup);
            return (int) Math.min(properties.getAttemptTimeoutSeconds(), remainingSeconds);
        }
    }

    private void setGenerationRetryWait(InterviewGenerationCoordinator.GenerationClaim claim,
                                        InterviewFailure failure, Instant nextRetryAt) {
        State state = find(claim.owner(), claim.sessionId());
        synchronized (state) {
            verifyActiveGeneration(state, claim.generationId(), claim.expectedRevision());
            var backup = state.backup();
            var generation = state.generation;
            state.generation = new InterviewSnapshot.Generation(generation.generationId(), generation.requestId(),
                    generation.kind(), generation.stage(), InterviewSnapshot.GenerationState.RETRY_WAIT,
                    generation.attempt(), generation.retryCount() + 1, generation.maxRetries(),
                    generation.startedAt(), generation.deadlineAt(), nextRetryAt, failure);
            persist(state, backup);
        }
    }

    private static boolean isActiveGeneration(InterviewSnapshot.GenerationState s) {
        return s == InterviewSnapshot.GenerationState.QUEUED
                || s == InterviewSnapshot.GenerationState.RUNNING
                || s == InterviewSnapshot.GenerationState.RETRY_WAIT;
    }

    private static void verifyActiveGeneration(State state, String generationId, long expectedRevision) {
        var g = state.generation;
        if (g == null || !generationId.equals(g.generationId()))
            throw new InterviewException(409, "生成操作不匹配，请刷新面试记录");
        if (!isActiveGeneration(g.state()))
            throw new InterviewException(409, "生成操作已结束，不能重复提交");
        if (expectedRevision != state.revision)
            throw new InterviewException(409, "状态已变更，生成结果已失效");
    }

    private static InterviewSnapshot.Generation withState(InterviewSnapshot.Generation g,
                                                          InterviewSnapshot.GenerationState state,
                                                          InterviewFailure failure) {
        return new InterviewSnapshot.Generation(g.generationId(), g.requestId(), g.kind(), g.stage(),
                state, g.attempt(), g.retryCount(), g.maxRetries(),
                g.startedAt(), g.deadlineAt(), g.nextRetryAt(), failure == null ? g.failure() : failure);
    }

    public InterviewAgent.InterviewReport report(String owner, String id, AiClient ai) {
        return report(owner, id, () -> ai, false);
    }

    public record ReportTask(InterviewAgent.InterviewReport report, String operationId, boolean enqueue) {}
    public record ReviewTask(InterviewAgent.InterviewReport report, String operationId, boolean enqueue) {}
    public record PracticeTask(Session session, String rootQuestionId, String requestId, boolean enqueue) {}
    public record PracticeContext(String resume, String role, InterviewAgent.AssessmentInput input, List<Turn> history) {}
    public record AttemptTask(InterviewAttempt attempt, boolean enqueue) {}
    public record AttemptContext(String resume, String role, InterviewAgent.AssessmentInput input,
            List<Turn> history, InterviewAssessment baseline, String rubricVersion, String operationId) {}

    /** QUICK-only, atomic close-and-queue so no unprompted first answer can race with displayed feedback. */
    public PracticeTask queuePracticeFeedback(String owner, String id, String questionId, String requestId, long expectedRevision) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 120)
            throw new IllegalArgumentException("反馈请求 ID 不能为空且不能超过 120 字");
        State state = find(owner, id);
        synchronized (state) {
            if (state.plan == null || !"experience-v1".equals(state.plan.policyVersion())
                    || state.plan.mode() != InterviewPlan.Mode.QUICK)
                throw new InterviewException(409, "只有快速练习可以在题组结束后查看教学反馈");
            if (!"ACTIVE".equals(state.status)) throw new InterviewException(409, "本轮已结束");
            Context context = state.contexts.get(questionId);
            if (context == null) throw new InterviewException(404, "题目不存在");
            String rootId = context.kind() == Kind.FOLLOW_UP ? context.parentQuestionId() : questionId;
            Turn root = state.turns.stream().filter(t -> t.questionId().equals(rootId)).findFirst().orElse(null);
            if (root == null || root.answer() == null || context.kind().isSpecialty())
                throw new InterviewException(409, "请先保存主问题回答，再查看题组反馈");
            var previous = state.practiceFeedbacks.get(rootId);
            if (previous != null && previous.requestId().equals(requestId))
                return new PracticeTask(state.snapshot(), rootId, requestId, false);
            if (previous != null && previous.status() != InterviewPracticeFeedback.Status.FAILED)
                throw new InterviewException(409, "这个题组已经关闭或正在生成反馈");
            if (state.closedRootIds.contains(rootId)) throw new InterviewException(409, "题组已关闭，不能再次查看反馈");
            if (expectedRevision != state.revision) throw new InterviewException(409, "状态已变化，请刷新后重试");
            var backup = state.backup();
            state.closedRootIds.add(rootId);
            state.currentQuestionId = null;
            state.pending = null;
            if (state.generation != null && isActiveGeneration(state.generation.state()))
                state.generation = withState(state.generation, InterviewSnapshot.GenerationState.CANCELLED,
                        new InterviewFailure(InterviewFailure.Code.INTERRUPTED, false, "题组已关闭并开始反馈", null));
            state.practiceFeedbacks.put(rootId, new InterviewPracticeFeedback(requestId,
                    InterviewPracticeFeedback.Status.QUEUED, null, ""));
            state.revision++;
            persist(state, backup);
            return new PracticeTask(state.snapshot(), rootId, requestId, true);
        }
    }

    public PracticeContext practiceContext(String owner, String id, String rootId, String requestId) {
        State state = find(owner, id);
        synchronized (state) {
            var feedback = state.practiceFeedbacks.get(rootId);
            if (feedback == null || !feedback.requestId().equals(requestId)
                    || feedback.status() != InterviewPracticeFeedback.Status.RUNNING)
                throw new InterviewException(409, "反馈任务已失效");
            var root = state.turns.stream().filter(t -> t.questionId().equals(rootId)).findFirst().orElseThrow();
            var followUps = state.turns.stream().filter(t -> {
                Context c = state.contexts.get(t.questionId());
                return c != null && rootId.equals(c.parentQuestionId()) && t.answer() != null;
            }).toList();
            var input = new InterviewAgent.AssessmentInput(root, state.questionContracts.get(rootId), followUps);
            return new PracticeContext(state.resume, state.role, input, List.copyOf(state.turns));
        }
    }

    public boolean startPracticeFeedback(String owner, String id, String rootId, String requestId) {
        State state = find(owner, id);
        synchronized (state) {
            var old = state.practiceFeedbacks.get(rootId);
            if (old == null || !old.requestId().equals(requestId) || old.status() != InterviewPracticeFeedback.Status.QUEUED) return false;
            var backup = state.backup();
            state.practiceFeedbacks.put(rootId, new InterviewPracticeFeedback(requestId, InterviewPracticeFeedback.Status.RUNNING, null, ""));
            state.stateVersion++;
            persist(state, backup);
            return true;
        }
    }

    public void completePracticeFeedback(String owner, String id, String rootId, String requestId, InterviewAssessment assessment) {
        State state = find(owner, id);
        synchronized (state) {
            var old = state.practiceFeedbacks.get(rootId);
            if (old == null || !old.requestId().equals(requestId) || old.status() != InterviewPracticeFeedback.Status.RUNNING) return;
            if (assessment == null) throw new InterviewException(502, "反馈结果为空");
            var backup = state.backup();
            state.assessments.put(rootId, assessment);
            state.practiceFeedbacks.put(rootId, new InterviewPracticeFeedback(requestId, InterviewPracticeFeedback.Status.COMPLETE, assessment, ""));
            state.stateVersion++;
            persist(state, backup);
        }
    }

    public void failPracticeFeedback(String owner, String id, String rootId, String requestId, String error) {
        State state = find(owner, id);
        synchronized (state) {
            var old = state.practiceFeedbacks.get(rootId);
            if (old == null || !old.requestId().equals(requestId)
                    || old.status() != InterviewPracticeFeedback.Status.RUNNING && old.status() != InterviewPracticeFeedback.Status.QUEUED) return;
            var backup = state.backup();
            state.practiceFeedbacks.put(rootId, new InterviewPracticeFeedback(requestId, InterviewPracticeFeedback.Status.FAILED, null,
                    error == null || error.isBlank() ? "本题反馈暂不可用，可以继续下一题。" : error));
            state.stateVersion++;
            persist(state, backup);
        }
    }

    public AttemptTask submitAttempt(String owner, String id, String questionId, String requestId, String answer) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 120)
            throw new IllegalArgumentException("重答请求 ID 不能为空且不能超过 120 字");
        if (answer == null || answer.isBlank() || answer.strip().length() > 5000)
            throw new IllegalArgumentException("重答须为 1–5000 字");
        State state = find(owner, id);
        synchronized (state) {
            var duplicate = state.attempts.values().stream().filter(a -> a.requestId().equals(requestId)).findFirst();
            if (duplicate.isPresent()) {
                var old = duplicate.get();
                if (!old.questionId().equals(questionId) || !old.answer().equals(answer.strip()))
                    throw new InterviewException(409, "重答请求 ID 已用于另一份内容");
                return new AttemptTask(old, false);
            }
            Context context = state.contexts.get(questionId);
            if (context == null || context.kind() == Kind.FOLLOW_UP)
                throw new InterviewException(404, "根问题不存在");
            String rootId = questionId;
            if ("ACTIVE".equals(state.status)) {
                if (state.plan == null || state.plan.mode() != InterviewPlan.Mode.QUICK
                        || !state.closedRootIds.contains(rootId))
                    throw new InterviewException(409, "完整面试请结束后再重答；快速练习需先关闭本题组");
            } else if (!"FINISHED".equals(state.status)) throw new InterviewException(409, "本轮状态不支持重答");
            Turn original = state.turns.stream().filter(t -> t.questionId().equals(rootId)).findFirst().orElse(null);
            if (original == null || original.answer() == null) throw new InterviewException(409, "首答尚未保存，不能创建重答");
            int count = (int) state.attempts.values().stream().filter(a -> a.questionId().equals(rootId)).count();
            if (count >= 5) throw new InterviewException(409, "每道题最多保存五版重答");
            var contract = state.questionContracts.get(rootId);
            if (contract == null) throw new InterviewException(409, "首题评分标准暂不可用，不能重答");
            var attempt = new InterviewAttempt(UUID.randomUUID().toString(), requestId, UUID.randomUUID().toString(),
                    rootId, count + 1, answer, InterviewAttempt.Status.PENDING, null, null, "",
                    contract.rubricVersion(), "");
            var backup = state.backup();
            state.attempts.put(attempt.attemptId(), attempt);
            state.stateVersion++;
            persist(state, backup);
            return new AttemptTask(attempt, true);
        }
    }

    public InterviewAttempt getAttempt(String owner, String id, String attemptId) {
        State state = find(owner, id);
        synchronized (state) {
            InterviewAttempt attempt = state.attempts.get(attemptId);
            if (attempt == null) throw new InterviewException(404, "重答记录不存在");
            return attempt;
        }
    }

    public List<InterviewAttempt> listAttempts(String owner, String id, String questionId) {
        State state = find(owner, id);
        synchronized (state) {
            if (!state.contexts.containsKey(questionId)) throw new InterviewException(404, "根问题不存在");
            return state.attempts.values().stream().filter(attempt -> attempt.questionId().equals(questionId))
                    .sorted(java.util.Comparator.comparingInt(InterviewAttempt::version)).toList();
        }
    }

    public AttemptTask retryAttempt(String owner, String id, String attemptId, String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 120)
            throw new IllegalArgumentException("补评请求 ID 不能为空且不能超过 120 字");
        State state = find(owner, id);
        synchronized (state) {
            InterviewAttempt old = state.attempts.get(attemptId);
            if (old == null) throw new InterviewException(404, "重答记录不存在");
            String retryOperationId = UUID.nameUUIDFromBytes((attemptId + "\n" + requestId)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            if (old.operationId().equals(retryOperationId)) return new AttemptTask(old, false);
            if (old.status() == InterviewAttempt.Status.PENDING || old.status() == InterviewAttempt.Status.RUNNING)
                return new AttemptTask(old, false);
            if (old.status() != InterviewAttempt.Status.FAILED) throw new InterviewException(409, "只有评分失败的重答可以补评");
            var backup = state.backup();
            var retry = new InterviewAttempt(old.attemptId(), old.requestId(), retryOperationId, old.questionId(),
                    old.version(), old.answer(), InterviewAttempt.Status.PENDING, null, null, "", old.rubricVersion(), "");
            state.attempts.put(attemptId, retry);
            state.stateVersion++;
            persist(state, backup);
            return new AttemptTask(retry, true);
        }
    }

    public boolean startAttempt(String owner, String id, String attemptId, String operationId) {
        State state = find(owner, id);
        synchronized (state) {
            var old = state.attempts.get(attemptId);
            if (old == null || !old.operationId().equals(operationId) || old.status() != InterviewAttempt.Status.PENDING) return false;
            var backup = state.backup();
            state.attempts.put(attemptId, new InterviewAttempt(old.attemptId(), old.requestId(), old.operationId(), old.questionId(),
                    old.version(), old.answer(), InterviewAttempt.Status.RUNNING, null, null, "", old.rubricVersion(), ""));
            state.stateVersion++; persist(state, backup); return true;
        }
    }

    public AttemptContext attemptContext(String owner, String id, String attemptId, String operationId) {
        State state = find(owner, id);
        synchronized (state) {
            var attempt = state.attempts.get(attemptId);
            if (attempt == null || !attempt.operationId().equals(operationId) || attempt.status() != InterviewAttempt.Status.RUNNING)
                throw new InterviewException(409, "重答评分操作已失效");
            Turn original = state.turns.stream().filter(t -> t.questionId().equals(attempt.questionId())).findFirst().orElseThrow();
            var response = new Turn(original.questionId(), original.question(), attempt.answer());
            var input = new InterviewAgent.AssessmentInput(response, state.questionContracts.get(attempt.questionId()), List.of());
            return new AttemptContext(state.resume, state.role, input, List.copyOf(state.turns),
                    state.assessments.get(attempt.questionId()), attempt.rubricVersion(), operationId);
        }
    }

    public void completeAttempt(String owner, String id, String attemptId, String operationId, InterviewAssessment feedback) {
        State state = find(owner, id);
        synchronized (state) {
            var old = state.attempts.get(attemptId);
            if (old == null || !old.operationId().equals(operationId) || old.status() != InterviewAttempt.Status.RUNNING) return;
            if (feedback == null || !old.questionId().equals(feedback.questionId())) throw new InterviewException(502, "重答评分结果无效");
            var comparison = InterviewAttemptComparison.compare(state.assessments.get(old.questionId()), feedback,
                    state.questionContracts.get(old.questionId()).rubricVersion(), old.rubricVersion());
            var backup = state.backup();
            state.attempts.put(attemptId, new InterviewAttempt(old.attemptId(), old.requestId(), old.operationId(), old.questionId(),
                    old.version(), old.answer(), InterviewAttempt.Status.COMPLETE, feedback, comparison,
                    feedback.aiModel(), old.rubricVersion(), ""));
            state.stateVersion++; persist(state, backup);
        }
    }

    public void failAttempt(String owner, String id, String attemptId, String operationId, String error) {
        State state = find(owner, id);
        synchronized (state) {
            var old = state.attempts.get(attemptId);
            if (old == null || !old.operationId().equals(operationId)
                    || old.status() != InterviewAttempt.Status.PENDING && old.status() != InterviewAttempt.Status.RUNNING) return;
            var backup = state.backup();
            state.attempts.put(attemptId, new InterviewAttempt(old.attemptId(), old.requestId(), old.operationId(), old.questionId(),
                    old.version(), old.answer(), InterviewAttempt.Status.FAILED, null, null, "", old.rubricVersion(),
                    error == null || error.isBlank() ? "重答评分暂不可用，可补评；答案已保存。" : error));
            state.stateVersion++; persist(state, backup);
        }
    }
    public record ReviewContext(Map<String, String> answers, Map<String, String> feedback,
                                Set<String> scoredQuestionIds, long sourceVersion) {}

    public record FocusedPracticeContext(InterviewPlan.ResumeSource source, String targetRole,
            String jobDescription, InterviewPlan.Level level, InterviewPlan.CareerStage careerStage,
            String sourceSessionId, List<InterviewPlan.PracticeTarget> targets) {}

    /** Build focused targets only from the current, completed, evidence-validated review summary. */
    public FocusedPracticeContext focusedPracticeContext(String owner, String id, List<String> issueIds) {
        State state = find(owner, id);
        synchronized (state) {
            if (!"FINISHED".equals(state.status) || !"SUCCEEDED".equals(state.scoringState)
                    || state.reviewSummary == null || state.reviewSummary.status() != InterviewReviewSummary.Status.COMPLETE
                    || state.reviewSummary.sourceVersion() != state.scoringStateVersion)
                throw new InterviewException(409, "需要先完成本轮报告重点归纳，再准备薄弱项练习");
            if (issueIds == null || issueIds.isEmpty() || issueIds.size() > 3
                    || issueIds.stream().anyMatch(value -> value == null || value.isBlank())
                    || issueIds.stream().distinct().count() != issueIds.size())
                throw new IllegalArgumentException("请选择 1–3 个不同的报告重点");
            var selected = new LinkedHashMap<String, InterviewReviewSummary.PriorityIssue>();
            for (var issue : state.reviewSummary.priorityIssues()) selected.put(issue.questionId(), issue);
            if (!selected.keySet().containsAll(issueIds))
                throw new IllegalArgumentException("练习目标必须来自本轮已验证报告重点");
            var targets = issueIds.stream().map(selected::get).map(issue -> new InterviewPlan.PracticeTarget(
                    issue.questionId(), issue.issue(), issue.suggestedAction(), List.of(issue.questionId()))).toList();
            var original = state.plan;
            var source = original == null
                    ? new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, state.materialId)
                    : original.source();
            return new FocusedPracticeContext(source, state.role, original == null ? "" : original.jobDescription(),
                    original == null ? InterviewPlan.Level.STANDARD : original.level(),
                    original == null ? InterviewPlan.CareerStage.CAMPUS : original.careerStage(), id, targets);
        }
    }

    public void validatePracticeSource(String owner, String id, List<InterviewPlan.PracticeTarget> targets) {
        State state = find(owner, id);
        synchronized (state) {
            if (!"FINISHED".equals(state.status) || !"SUCCEEDED".equals(state.scoringState) || state.reviewSummary == null
                    || state.reviewSummary.status() != InterviewReviewSummary.Status.COMPLETE
                    || state.reviewSummary.sourceVersion() != state.scoringStateVersion)
                throw new InterviewException(409, "来源报告重点已变化，请重新选择练习目标");
            var expected = new LinkedHashMap<String, InterviewReviewSummary.PriorityIssue>();
            state.reviewSummary.priorityIssues().forEach(issue -> expected.put(issue.questionId(), issue));
            if (targets == null || targets.isEmpty() || targets.size() > 3 || targets.stream().anyMatch(target -> {
                var issue = expected.get(target.issueId());
                return issue == null || !issue.issue().equals(target.title())
                        || !issue.suggestedAction().equals(target.action())
                        || !target.sourceQuestionIds().equals(List.of(issue.questionId()));
            })) throw new InterviewException(409, "来源报告重点已变化，请重新选择练习目标");
        }
    }

    public ReviewTask queueReview(String owner, String id, String requestId, boolean retry) {
        State state = find(owner, id);
        synchronized (state) {
            if (!"FINISHED".equals(state.status)) throw new InterviewException(409, "面试尚未结束，不能归纳报告重点");
            var current = reportSnapshotLocked(state);
            if (!"SUCCEEDED".equals(state.scoringState)) return new ReviewTask(current, state.reviewOperationId, false);
            if (state.reviewSummary != null && state.reviewSummary.sourceVersion() == state.scoringStateVersion) {
                if (state.reviewSummary.status() == InterviewReviewSummary.Status.COMPLETE
                        || state.reviewSummary.status() == InterviewReviewSummary.Status.PENDING
                        || (!retry && state.reviewSummary.status() == InterviewReviewSummary.Status.UNAVAILABLE))
                    return new ReviewTask(current, state.reviewOperationId, false);
                if (retry && Objects.equals(state.reviewRequestId, requestId)) return new ReviewTask(current, state.reviewOperationId, false);
            }
            var backup = state.backup();
            state.reviewRequestId = requestId == null || requestId.isBlank() ? "review-get-" + id : requestId.strip();
            state.reviewOperationId = UUID.randomUUID().toString();
            state.reviewSummary = new InterviewReviewSummary(InterviewReviewSummary.Status.PENDING,
                    state.scoringStateVersion, List.of(), "");
            persist(state, backup);
            return new ReviewTask(reportSnapshotLocked(state), state.reviewOperationId, true);
        }
    }

    public boolean startReview(String owner, String id, String operationId) {
        State state = find(owner, id);
        synchronized (state) {
            return Objects.equals(state.reviewOperationId, operationId) && "FINISHED".equals(state.status)
                    && "SUCCEEDED".equals(state.scoringState) && state.reviewSummary != null
                    && state.reviewSummary.status() == InterviewReviewSummary.Status.PENDING
                    && state.reviewSummary.sourceVersion() == state.scoringStateVersion;
        }
    }

    public ReviewContext reviewContext(String owner, String id, String operationId) {
        State state = find(owner, id);
        synchronized (state) {
            if (!Objects.equals(state.reviewOperationId, operationId) || state.reviewSummary == null
                    || state.reviewSummary.status() != InterviewReviewSummary.Status.PENDING
                    || state.reviewSummary.sourceVersion() != state.scoringStateVersion)
                throw new InterviewException(409, "评分版本已变化，旧报告归纳已失效");
            var feedbacks = assembleReport(state).turnFeedbacks().stream().filter(f -> "SCORED".equals(f.status())).toList();
            var answers = new LinkedHashMap<String, String>(); var feedback = new LinkedHashMap<String, String>();
            for (var item : feedbacks) {
                if (item.questionId() == null || item.answer() == null) continue;
                answers.put(item.questionId(), item.answer());
                feedback.put(item.questionId(), "问题=" + item.question() + "；改进=" + item.improvement()
                        + "；缺口=" + String.join("、", item.missingPoints()) + "；证据=" + String.join("、", item.evidence()));
            }
            return new ReviewContext(Map.copyOf(answers), Map.copyOf(feedback), Set.copyOf(answers.keySet()), state.scoringStateVersion);
        }
    }

    public boolean completeReview(String owner, String id, String operationId,
            List<InterviewReviewSummary.PriorityIssue> issues) {
        State state = find(owner, id);
        synchronized (state) {
            if (!Objects.equals(state.reviewOperationId, operationId) || state.reviewSummary == null
                    || state.reviewSummary.status() != InterviewReviewSummary.Status.PENDING
                    || state.reviewSummary.sourceVersion() != state.scoringStateVersion || !"SUCCEEDED".equals(state.scoringState)) return false;
            var context = reviewContext(owner, id, operationId);
            var checked = InterviewReviewSummary.validate(issues,
                    new InterviewReviewSummary.ValidationContext(context.answers(), context.scoredQuestionIds()));
            var backup = state.backup();
            state.reviewSummary = new InterviewReviewSummary(InterviewReviewSummary.Status.COMPLETE,
                    context.sourceVersion(), checked, "");
            persist(state, backup); return true;
        }
    }

    public void failReview(String owner, String id, String operationId, String message) {
        State state = find(owner, id);
        synchronized (state) {
            if (!Objects.equals(state.reviewOperationId, operationId) || state.reviewSummary == null
                    || state.reviewSummary.status() != InterviewReviewSummary.Status.PENDING
                    || state.reviewSummary.sourceVersion() != state.scoringStateVersion) return;
            var backup = state.backup();
            state.reviewSummary = new InterviewReviewSummary(InterviewReviewSummary.Status.UNAVAILABLE,
                    state.scoringStateVersion, List.of(), message == null ? "报告重点归纳暂不可用，可重试。" : "报告重点归纳暂不可用，可重试。");
            persist(state, backup);
        }
    }

    private InterviewAgent.InterviewReport reportSnapshotLocked(State state) {
        var report = assembleReport(state).withScoring(state.scoringState, state.scoringOperationId,
                state.scoringStateVersion, state.scoringError);
        var review = state.reviewSummary == null
                ? new InterviewReviewSummary(InterviewReviewSummary.Status.PENDING, state.scoringStateVersion, List.of(), "")
                : state.reviewSummary;
        return report.withReview(review);
    }

    /** Queue a durable scoring operation without holding the session monitor during model I/O. */
    public ReportTask queueReport(String owner, String id, String requestId, boolean retry) {
        State state = find(owner, id);
        synchronized (state) {
            if (!"FINISHED".equals(state.status)) throw new InterviewException(409, "面试尚未结束，不能评分");
            String request = requestId == null || requestId.isBlank() ? "report-get-" + id : requestId.strip();
            var current = assembleReport(state).withScoring(state.scoringState, state.scoringOperationId,
                    state.scoringStateVersion, state.scoringError);
            boolean hasAnswersNeedingScores = state.turns.stream().anyMatch(turn -> turn.answer() != null
                    && state.contexts.get(turn.questionId()).kind() != Kind.FOLLOW_UP
                    && !state.assessments.containsKey(turn.questionId()));
            if (!hasAnswersNeedingScores) {
                if (!"SUCCEEDED".equals(state.scoringState)) {
                    var backup = state.backup();
                    state.scoringState = "SUCCEEDED"; state.scoringError = ""; state.scoringStateVersion++;
                    state.cachedReport = current;
                    persist(state, backup);
                }
                return new ReportTask(current.withScoring(state.scoringState, state.scoringOperationId,
                        state.scoringStateVersion, state.scoringError), state.scoringOperationId, false);
            }
            if ("QUEUED".equals(state.scoringState) || "RUNNING".equals(state.scoringState)
                    || ("FAILED".equals(state.scoringState) && !retry)
                    || (retry && request.equals(state.scoringRequestId)))
                return new ReportTask(current.withScoring(state.scoringState, state.scoringOperationId,
                        state.scoringStateVersion, state.scoringError), state.scoringOperationId, false);
            var backup = state.backup();
            state.scoringRequestId = request;
            state.scoringOperationId = UUID.randomUUID().toString();
            state.scoringState = "QUEUED";
            state.scoringError = "";
            state.scoringStateVersion++;
            state.reviewSummary = null; state.reviewOperationId = null; state.reviewRequestId = null;
            persist(state, backup);
            return new ReportTask(assembleReport(state).withScoring(state.scoringState, state.scoringOperationId,
                    state.scoringStateVersion, state.scoringError), state.scoringOperationId, true);
        }
    }

    public boolean startReportScoring(String owner, String id, String operationId) {
        State state = find(owner, id);
        synchronized (state) {
            if (!Objects.equals(state.scoringOperationId, operationId) || !"QUEUED".equals(state.scoringState)) return false;
            var backup = state.backup(); state.scoringState = "RUNNING"; state.scoringStateVersion++;
            persist(state, backup); return true;
        }
    }

    public void completeReportScoring(String owner, String id, String operationId) {
        State state = find(owner, id);
        synchronized (state) {
            if (!Objects.equals(state.scoringOperationId, operationId)) return;
            var backup = state.backup(); state.cachedReport = assembleReport(state);
            state.scoringState = "SUCCEEDED"; state.scoringError = ""; state.scoringStateVersion++;
            persist(state, backup);
        }
    }

    public void failReportScoring(String owner, String id, String operationId, Throwable cause) {
        State state = find(owner, id);
        synchronized (state) {
            if (!Objects.equals(state.scoringOperationId, operationId)) return;
            var backup = state.backup(); state.scoringState = "FAILED";
            state.scoringError = cause == null || cause.getMessage() == null ? "评分暂不可用，请稍后重试。" : cause.getMessage();
            state.scoringStateVersion++; persist(state, backup);
        }
    }

    public InterviewAgent.InterviewReport reportSnapshot(String owner, String id) {
        State state = find(owner, id);
        synchronized (state) {
            if (!"FINISHED".equals(state.status)) throw new InterviewException(409, "面试尚未结束，不能查看报告");
            return reportSnapshotLocked(state);
        }
    }

    private static InterviewAgent.InterviewReport assembleReport(State state) {
        return new InterviewReportAssembler().assemble(state.count, List.copyOf(state.turns),
                Map.copyOf(state.questionContracts), Map.copyOf(state.assessments), Map.copyOf(state.contexts),
                state.finishReason, state.plan, state.progress(), Set.copyOf(state.closedRootIds),
                Set.copyOf(state.withdrawnQuestionIds));
    }

    public InterviewAgent.InterviewReport report(String owner, String id, Supplier<AiClient> client, boolean retry) {
        State state = find(owner, id);
        synchronized (state) {
            if (!state.status.equals("FINISHED"))
                throw new InterviewException(409, "面试尚未结束，请先完成所有回答或手动结束面试");
        }
        synchronized (state.reportLock) {
            find(owner, id); // The session may have expired while this request waited for an earlier report.
            List<Turn> turns;
            Map<String, InterviewQuestion> contracts;
            List<InterviewAgent.AssessmentInput> inputs;
            String finishReason;
            synchronized (state) {
                if (!retry && state.cachedReport != null) return state.cachedReport;
                turns = List.copyOf(state.turns);
                contracts = Map.copyOf(state.questionContracts);
                finishReason = state.finishReason;
                inputs = turns.stream().filter(t -> t.answer() != null && state.contexts.get(t.questionId()).kind() != Kind.FOLLOW_UP
                                && !state.assessments.containsKey(t.questionId()))
                        .map(t -> new InterviewAgent.AssessmentInput(t, contracts.get(t.questionId()),
                                turns.stream().filter(f -> t.questionId().equals(state.contexts.get(f.questionId()).parentQuestionId())).toList())).toList();
                if (inputs.isEmpty() && state.cachedReport != null) return state.cachedReport;
            }
            // Retain the report-specific lock for single-flight generation, but never block session reads on network IO.
            AiClient ai = inputs.isEmpty() ? null : client.get();
            for (int offset = 0; offset < inputs.size(); offset += 5) {
                var batch = inputs.subList(offset, Math.min(offset + 5, inputs.size()));
                var fresh = agent.assessAnswers(state.resume, state.role, batch, turns, ai);
                find(owner, id);
                synchronized (state) {
                    var backup = state.backup();
                    state.assessments.putAll(fresh);
                    persist(state, backup);
                }
            }
            find(owner, id);
            synchronized (state) {
                var backup = state.backup();
                state.cachedReport = new InterviewReportAssembler().assemble(state.count, turns, contracts, state.assessments, state.contexts, finishReason);
                persist(state, backup);
                return state.cachedReport;
            }
        }
    }
    /** Main contracts, including issued unanswered roots, share only the fixed 80% budget. */
    static Map<String, java.math.BigDecimal> weightShares(List<Turn> turns, Map<String, InterviewQuestion> contracts,
                                                       Map<String, Context> contexts) {
        var policy = new InterviewScoringPolicy();
        var mains = turns.stream().filter(t -> contexts.get(t.questionId()).kind() == Kind.MAIN).toList();
        var units = mains.stream().map(t -> contracts.get(t.questionId()))
                .map(q -> policy.weightUnits(q.importance(), q.difficulty())).toList();
        var percentages = units.isEmpty() ? List.<java.math.BigDecimal>of() : policy.percentages(units, 8000);
        Map<String, java.math.BigDecimal> result = new LinkedHashMap<>();
        for (int i = 0; i < mains.size(); i++) result.put(mains.get(i).questionId(), percentages.get(i));
        contexts.forEach((id, context) -> { if (context.kind().isSpecialty()) result.put(id, new java.math.BigDecimal("20.00")); });
        return result;
    }

    /**
     * 原子持久化：在 state 锁内构建候选快照→save→发布（计划 4.4 / T03）。
     * save 失败时回滚内存可变字段、不递增 stateVersion，旧文件保持可读。
     * store 为 null 时跳过落盘，仅做内存发布。
     *
     * @param backup 调用方在<b>修改前</b>捕获的备份；save 失败时 restore 到它。
     *               {@code null} 表示调用方不要求回滚（如新建会话失败将被整体丢弃）。
     */
    private void persist(State state, State.Backup backup) {
        if (store == null) { state.stateVersion++; return; }
        state.stateVersion++;
        var snapshot = state.toSnapshot();
        try {
            store.save(snapshot);
        } catch (RuntimeException e) {
            if (backup != null) state.restore(backup);
            if (e instanceof InterviewException ie) throw ie;
            var failure = new InterviewFailure(InterviewFailure.Code.STORAGE, false, "会话存储失败", null);
            var wrapped = new InterviewException(500, "会话存储失败", failure);
            wrapped.initCause(e);
            throw wrapped;
        }
    }

    private State find(String owner, String id) {
        synchronized (sessions) {
            State state = sessions.get(id);
            if (state == null && store != null) {
                // 重启或换实例后从持久化恢复；恢复的数据不再带运行期模型任务。
                var restored = store.find(owner, id);
                if (restored.isPresent()) {
                    state = State.fromSnapshot(restored.get());
                    sessions.put(state.id, state);
                }
            }
            if (state == null || !state.owner.equals(owner) || state.expires.isBefore(clock.instant()))
                throw new InterviewException(404, "面试不存在或已过期，请重新开始");
            return state;
        }
    }

    public void delete(String owner, String id) {
        synchronized (sessions) {
            State state = sessions.get(id);
            if (state != null && !state.owner.equals(owner))
                throw new InterviewException(404, "面试不存在或已过期，请重新开始");
            if (state == null && store != null) {
                var snapshot = store.find(owner, id);
                if (snapshot.isEmpty() && !store.isDeleted(owner, id))
                    throw new InterviewException(404, "面试不存在或已过期，请重新开始");
            }
            if (store != null) store.delete(owner, id);
            sessions.remove(id);
        }
    }

    public InterviewStore.HistoryPage history(String owner, int limit, String cursor) {
        if (store == null) return new InterviewStore.HistoryPage(List.of(), null);
        return store.list(owner, limit, cursor, clock.instant());
    }
}
