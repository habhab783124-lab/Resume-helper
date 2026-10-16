package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

@Service
public class InterviewService {
    public static final int MAIN_COUNT = 12, QUESTION_COUNT = 13;
    public enum Kind { MAIN, FOLLOW_UP, ALGORITHM }
    public record Context(Kind kind, String parentQuestionId, int mainQuestionIndex,
                          Hot100Catalog.Algorithm algorithm) {}
    private final InterviewAgent agent;
    private final Map<String, State> sessions = new HashMap<>();
    public InterviewService(InterviewAgent agent) { this.agent = agent; }
    public record Turn(String questionId, String question, String answer) {}
    public record Session(String sessionId, String materialId, String targetRole, int questionCount,
                          String status, List<Turn> turns, Turn currentQuestion, String pendingAnswer,
                          Map<String, InterviewQuestion.Overview> questionMetadata) {}
    private static final class State {
        final String id = UUID.randomUUID().toString(), owner, materialId, resume, role;
        final int count = QUESTION_COUNT;
        final Hot100Catalog.Problem selectedAlgorithm = Hot100Catalog.select();
        final Map<String, Context> contexts = new LinkedHashMap<>();
        final Instant expires = Instant.now().plusSeconds(86400);
        final List<Turn> turns = new ArrayList<>();
        final Map<String, InterviewQuestion> questionContracts = new LinkedHashMap<>();
        final Map<String, InterviewAssessment> assessments = new LinkedHashMap<>();
        final Object reportLock = new Object();
        InterviewAgent.InterviewReport cachedReport;
        String status = "ACTIVE", pending;
        State(String owner, String materialId, String resume, String role, int count) {
            this.owner = owner; this.materialId = materialId; this.resume = resume; this.role = role;
        }
        Session snapshot() {
            Turn current = status.equals("ACTIVE") && !turns.isEmpty() ? turns.get(turns.size()-1) : null;
            var metadata = new LinkedHashMap<String, InterviewQuestion.Overview>();
            var policy = new InterviewScoringPolicy();
            var shares = weightShares(turns, questionContracts, contexts);
            contexts.forEach((id, context) -> {
                var q = questionContracts.get(id);
                metadata.put(id, new InterviewQuestion.Overview(q.competency(), q.importance(), q.difficulty(),
                        context.kind() == Kind.FOLLOW_UP ? 0 : policy.weightUnits(q.importance(), q.difficulty()), q.weightReason(),
                        context.kind(), context.parentQuestionId(), context.mainQuestionIndex(), context.algorithm(), shares.get(id)));
            });
            return new Session(id, materialId, role, count, status, List.copyOf(turns), current, pending, Map.copyOf(metadata));
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
        State state = new State(owner, materialId, resume, role, count);
        synchronized (sessions) {
            sessions.values().removeIf(s -> s.expires.isBefore(Instant.now()));
            if (sessions.size() >= 2000 || sessions.values().stream().filter(s -> s.owner.equals(owner)).count() >= 20)
                throw new InterviewException(429, "暂存面试数量已达上限，请稍后再试");
            sessions.put(state.id, state);
        }
        synchronized (state) {
            try {
                InterviewQuestion question = agent.prepareQuestion(state.resume, role, List.of(), QUESTION_COUNT, ai);
                String questionId = UUID.randomUUID().toString();
                state.questionContracts.put(questionId, question);
                state.contexts.put(questionId, new Context(Kind.MAIN, null, 1, null));
                state.turns.add(new Turn(questionId, question.text(), null));
                return state.snapshot();
            } catch (RuntimeException e) {
                synchronized (sessions) { sessions.remove(state.id); }
                throw e;
            }
        }
    }
    public Session get(String owner, String id) {
        State state = find(owner, id);
        synchronized (state) { return state.snapshot(); }
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
            state.pending = value;
            Turn answered = new Turn(requested.questionId(), requested.question(), value);
            var context = state.contexts.get(questionId);
            if (context.kind() == Kind.ALGORITHM) {
                state.turns.set(last, answered); state.pending = null; state.status = "FINISHED";
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
                    question = agent.prepareAlgorithm(state.selectedAlgorithm, ai);
                    nextContext = new Context(Kind.ALGORITHM, null, QUESTION_COUNT, state.selectedAlgorithm.overview());
                } else {
                    question = proposal.question();
                    nextContext = new Context(Kind.MAIN, null, nextIndex, null);
                }
                String nextId = UUID.randomUUID().toString();
                state.turns.set(last, answered);
                state.questionContracts.put(nextId, question);
                state.contexts.put(nextId, nextContext);
                state.turns.add(new Turn(nextId, question.text(), null));
                state.pending = null;
            }
            return state.snapshot();
        }
    }
    public Session finish(String owner, String id) {
        State state = find(owner, id);
        synchronized (state) {
            if (state.pending != null && !state.turns.isEmpty()) {
                int last = state.turns.size()-1; Turn current = state.turns.get(last);
                state.turns.set(last, new Turn(current.questionId(), current.question(), state.pending)); state.pending = null;
            }
            state.status = "FINISHED";
            return state.snapshot();
        }
    }

    public InterviewAgent.InterviewReport report(String owner, String id, AiClient ai) {
        return report(owner, id, () -> ai, false);
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
            synchronized (state) {
                if (!retry && state.cachedReport != null) return state.cachedReport;
                turns = List.copyOf(state.turns);
                contracts = Map.copyOf(state.questionContracts);
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
                synchronized (state) { state.assessments.putAll(fresh); }
            }
            find(owner, id);
            synchronized (state) {
                state.cachedReport = new InterviewReportAssembler().assemble(state.count, turns, contracts, state.assessments, state.contexts);
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
        contexts.forEach((id, context) -> { if (context.kind() == Kind.ALGORITHM) result.put(id, new java.math.BigDecimal("20.00")); });
        return result;
    }

    private State find(String owner, String id) {
        synchronized (sessions) {
            State state = sessions.get(id);
            if (state == null || !state.owner.equals(owner) || state.expires.isBefore(Instant.now()))
                throw new InterviewException(404, "面试不存在或已过期，请重新开始");
            return state;
        }
    }
}
