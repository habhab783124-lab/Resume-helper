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
    public record Session(String sessionId, String materialId, String targetRole, int questionCount,
                          String status, String finishReason, long revision, long stateVersion,
                          List<Turn> turns, Turn currentQuestion, String pendingAnswer,
                          InterviewSnapshot.Generation generation,
                          Map<String, InterviewQuestion.Overview> questionMetadata, InterviewSpecialty specialty) {}

    private static final class State {
        final String id, owner, materialId, resume, role;
        final int count = QUESTION_COUNT;
        Hot100Catalog.Problem selectedAlgorithm;
        InterviewSpecialty specialty;
        InterviewPlan plan;
        final Map<String, Context> contexts = new LinkedHashMap<>();
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
            var shares = weightShares(turns, questionContracts, contexts);
            contexts.forEach((id, context) -> {
                var q = questionContracts.get(id);
                if (q == null) return; // 恢复中或未冻结的题不进 metadata
                metadata.put(id, new InterviewQuestion.Overview(q.competency(), q.importance(), q.difficulty(),
                        context.kind() == Kind.FOLLOW_UP ? 0 : policy.weightUnits(q.importance(), q.difficulty()), q.weightReason(),
                        context.kind(), context.parentQuestionId(), context.mainQuestionIndex(), context.algorithm(), shares.get(id)));
            });
            return new Session(id, materialId, role, count, status, finishReason, revision, stateVersion,
                    List.copyOf(turns), current, pending, generation, Map.copyOf(metadata), specialty);
        }

        /** 构建可落盘快照（计划 4.4 全字段）。运行期句柄（锁、Future、客户端）不进入。 */
        InterviewSnapshot toSnapshot() {
            var snapTurns = turns.stream().map(t -> new InterviewSnapshot.SnapshotTurn(
                    t.questionId(), t.question(), t.answer(),
                    t.answer() != null ? InterviewSnapshot.AnswerStatus.ANSWERED : InterviewSnapshot.AnswerStatus.UNANSWERED,
                    submissionIds.get(t.questionId()))).toList();
            var snapContexts = new LinkedHashMap<String, InterviewSnapshot.QuestionContext>();
            contexts.forEach((id, c) -> snapContexts.put(id,
                    new InterviewSnapshot.QuestionContext(c.kind(), c.parentQuestionId(), c.mainQuestionIndex(), c.algorithm())));
            return new InterviewSnapshot(InterviewSnapshot.SCHEMA_VERSION, id, owner, materialId, resume, role, count,
                    status, finishReason, pending, revision, stateVersion, snapTurns, questionContracts, snapContexts,
                    selectedAlgorithm, specialty, assessments, cachedReport, generation, startRequestId,
                    plan, InterviewSnapshot.Progress.empty(), Set.of(), createdAt, expires,
                    scoringState, scoringOperationId, scoringRequestId, scoringError, scoringStateVersion);
        }

        /** 从快照重建纯数据 State；运行期句柄（reportLock）新建。 */
        static State fromSnapshot(InterviewSnapshot s) {
            var st = new State(s.sessionId(), s.owner(), s.materialId(), s.resumeText(), s.targetRole(), s.createdAt(), s.expiresAt());
            st.status = s.status();
            st.finishReason = s.finishReason();
            st.pending = s.pendingAnswer();
            st.revision = s.revision();
            st.stateVersion = s.stateVersion();
            st.plan = s.plan();
            st.specialty = s.specialty();
            st.selectedAlgorithm = s.selectedAlgorithm();
            st.cachedReport = s.cachedReport();
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
                if (t.answer() == null) { st.currentQuestionId = t.questionId(); break; }
            }
            return st;
        }

        /** 备份本次写入前的可变字段，用于 save 失败回滚。 */
        Backup backup() {
            return new Backup(status, pending, finishReason, currentQuestionId, revision, stateVersion,
                    new ArrayList<>(turns), new LinkedHashMap<>(submissionIds), new LinkedHashMap<>(assessments),
                    cachedReport, generation, specialty, selectedAlgorithm, plan, scoringState, scoringOperationId,
                    scoringRequestId, scoringError, scoringStateVersion);
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
            Backup(String status, String pending, String finishReason, String currentQuestionId,
                   long revision, long stateVersion, List<Turn> turns, Map<String, String> submissionIds,
                   Map<String, InterviewAssessment> assessments, InterviewAgent.InterviewReport cachedReport,
                   InterviewSnapshot.Generation generation, InterviewSpecialty specialty,
                   Hot100Catalog.Problem selectedAlgorithm, InterviewPlan plan, String scoringState,
                   String scoringOperationId, String scoringRequestId, String scoringError, long scoringStateVersion) {
                this.status = status; this.pending = pending; this.finishReason = finishReason;
                this.currentQuestionId = currentQuestionId; this.revision = revision; this.stateVersion = stateVersion;
                this.turns = turns; this.submissionIds = submissionIds; this.assessments = assessments;
                this.cachedReport = cachedReport; this.generation = generation;
                this.specialty = specialty; this.selectedAlgorithm = selectedAlgorithm; this.plan = plan;
                this.scoringState = scoringState; this.scoringOperationId = scoringOperationId;
                this.scoringRequestId = scoringRequestId; this.scoringError = scoringError;
                this.scoringStateVersion = scoringStateVersion;
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
        State state = new State(UUID.randomUUID().toString(), owner, materialId, resume, role, now, now.plusSeconds(86400));
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
                if (!Objects.equals(state.materialId, materialId) || !state.resume.equals(resume)
                        || !state.role.equals(role))
                    throw new InterviewException(409, "开始请求 ID 已用于不同的简历或岗位");
            } else {
                Instant now = clock.instant();
                state = new State(UUID.randomUUID().toString(), owner, materialId, resume, role,
                        now, now.plusSeconds(86400));
                state.startRequestId = requestId;
                state.plan = InterviewPlan.legacyV3(
                        new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL,
                                materialId == null ? "" : materialId), role);
                sessions.values().removeIf(s -> s.expires.isBefore(now));
                if (sessions.size() >= 2000 || sessions.values().stream().filter(s -> s.owner.equals(owner)).count() >= 20)
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
                int nextIndex = context.mainQuestionIndex() + 1;
                InterviewAgent.Proposal proposal = followUpCount < 2
                        ? agent.prepareNext(state.resume, state.role, history, QUESTION_COUNT, nextIndex, true, ai)
                        : nextIndex <= MAIN_COUNT ? agent.prepareNext(state.resume, state.role, history, QUESTION_COUNT, nextIndex, false, ai)
                        : new InterviewAgent.Proposal(null, false);
                Context nextContext;
                InterviewQuestion question;
                if (proposal.followUp()) {
                    var parent = state.questionContracts.get(parentId);
                    // Freeze the parent's rubric; model-provided follow-up rubrics and weights are discarded.
                    question = new InterviewQuestion(proposal.question().text(), parent.competency(), parent.importance(),
                            parent.difficulty(), parent.weightReason(), parent.criteria(), parent.rubricVersion());
                    nextContext = new Context(Kind.FOLLOW_UP, parentId, context.mainQuestionIndex(), null);
                } else if (nextIndex == QUESTION_COUNT) {
                    if (state.specialty.kind() == InterviewSpecialty.Kind.ALGORITHM) {
                        question = agent.prepareAlgorithm(state.selectedAlgorithm, ai);
                        nextContext = new Context(Kind.ALGORITHM, null, QUESTION_COUNT, state.selectedAlgorithm.overview());
                    } else {
                        question = agent.prepareCase(state.specialty, state.role, ai);
                        nextContext = new Context(Kind.CASE_STUDY, null, QUESTION_COUNT, null);
                    }
                } else {
                    question = proposal.question();
                    nextContext = new Context(Kind.MAIN, null, nextIndex, null);
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
                                   InterviewSpecialty specialty, Hot100Catalog.Problem selectedAlgorithm) {
        public GenerationResult {
            Objects.requireNonNull(question, "question");
            Objects.requireNonNull(kind, "kind");
        }
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
                if (state.turns.isEmpty() || state.turns.get(state.turns.size() - 1).answer() == null)
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
            String questionId = UUID.randomUUID().toString();
            state.questionContracts.put(questionId, result.question());
            state.contexts.put(questionId, new Context(result.kind(), result.parentQuestionId(),
                    result.mainQuestionIndex(), result.algorithm()));
            state.turns.add(new Turn(questionId, result.question().text(), null));
            state.currentQuestionId = questionId;
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
        InterviewSpecialty specialty;
        Hot100Catalog.Problem selected;
        synchronized (state) {
            verifyActiveGeneration(state, claim.generationId(), claim.expectedRevision());
            var backup = state.backup();
            state.generation = withState(state.generation, InterviewSnapshot.GenerationState.RUNNING, null);
            state.stateVersion++;
            persist(state, backup);
            resume = state.resume;
            role = state.role;
            history = List.copyOf(state.turns);
            specialty = state.specialty;
            selected = state.selectedAlgorithm;
            if (claim.kind() == InterviewSnapshot.GenerationKind.NEXT) {
                Turn last = history.get(history.size() - 1);
                context = state.contexts.get(last.questionId());
                String parentId = context.kind() == Kind.FOLLOW_UP ? context.parentQuestionId() : last.questionId();
                parentContract = state.questionContracts.get(parentId);
                followUpCount = state.contexts.values().stream().filter(c -> parentId.equals(c.parentQuestionId())).count();
            }
        }
        if (claim.kind() == InterviewSnapshot.GenerationKind.OPENING) {
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
        boolean allowFollowUp = followUpCount < 2;
        InterviewAgent.Proposal proposal = allowFollowUp
                ? withRetries(claim, InterviewSnapshot.GenerationStage.NEXT_DECISION, ai,
                    timeout -> agent.prepareNext(resume, role, history, QUESTION_COUNT, nextIndex, true, ai, timeout))
                : nextIndex <= MAIN_COUNT
                    ? withRetries(claim, InterviewSnapshot.GenerationStage.NEXT_DECISION, ai,
                        timeout -> agent.prepareNext(resume, role, history, QUESTION_COUNT, nextIndex, false, ai, timeout))
                    : new InterviewAgent.Proposal(null, false);
        if (proposal.followUp()) {
            InterviewQuestion q = proposal.question();
            InterviewQuestion frozen = new InterviewQuestion(q.text(), parentContract.competency(), parentContract.importance(),
                    parentContract.difficulty(), parentContract.weightReason(), parentContract.criteria(), parentContract.rubricVersion());
            String parentId = context.kind() == Kind.FOLLOW_UP ? context.parentQuestionId() : last.questionId();
            return new GenerationResult(frozen, Kind.FOLLOW_UP, parentId, context.mainQuestionIndex(), null, null, null);
        }
        if (nextIndex == QUESTION_COUNT) {
            InterviewQuestion q = specialty.kind() == InterviewSpecialty.Kind.ALGORITHM
                    ? withRetries(claim, InterviewSnapshot.GenerationStage.SPECIALTY, ai,
                        timeout -> agent.prepareAlgorithm(selected, ai, timeout))
                    : withRetries(claim, InterviewSnapshot.GenerationStage.SPECIALTY, ai,
                        timeout -> agent.prepareCase(specialty, role, ai, timeout));
            return new GenerationResult(q,
                    specialty.kind() == InterviewSpecialty.Kind.ALGORITHM ? Kind.ALGORITHM : Kind.CASE_STUDY,
                    null, QUESTION_COUNT, specialty.kind() == InterviewSpecialty.Kind.ALGORITHM ? selected.overview() : null,
                    null, null);
        }
        return new GenerationResult(proposal.question(), Kind.MAIN, null, nextIndex, null, null, null);
    }

    @FunctionalInterface
    private interface TimedStage<T> { T run(int timeoutSeconds); }

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
            return assembleReport(state).withScoring(state.scoringState, state.scoringOperationId,
                    state.scoringStateVersion, state.scoringError);
        }
    }

    private static InterviewAgent.InterviewReport assembleReport(State state) {
        return new InterviewReportAssembler().assemble(state.count, List.copyOf(state.turns),
                Map.copyOf(state.questionContracts), Map.copyOf(state.assessments), Map.copyOf(state.contexts), state.finishReason);
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
}
