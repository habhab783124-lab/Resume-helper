package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

@Service
public class InterviewService {
    private final InterviewAgent agent;
    private final Map<String, State> sessions = new HashMap<>();
    public InterviewService(InterviewAgent agent) { this.agent = agent; }
    public record Turn(String questionId, String question, String answer) {}
    public record Session(String sessionId, String materialId, String targetRole, int questionCount,
                          String status, List<Turn> turns, Turn currentQuestion, String pendingAnswer,
                          Map<String, InterviewQuestion.Overview> questionMetadata) {}
    private static final class State {
        final String id = UUID.randomUUID().toString(), owner, materialId, resume, role;
        final int count;
        final Instant expires = Instant.now().plusSeconds(86400);
        final List<Turn> turns = new ArrayList<>();
        final Map<String, InterviewQuestion> questionContracts = new LinkedHashMap<>();
        final Map<String, InterviewAssessment> assessments = new LinkedHashMap<>();
        final Object reportLock = new Object();
        InterviewAgent.InterviewReport cachedReport;
        String status = "ACTIVE", pending;
        State(String owner, String materialId, String resume, String role, int count) {
            this.owner = owner; this.materialId = materialId; this.resume = resume; this.role = role; this.count = count;
        }
        Session snapshot() {
            Turn current = status.equals("ACTIVE") && !turns.isEmpty() ? turns.get(turns.size()-1) : null;
            var metadata = new LinkedHashMap<String, InterviewQuestion.Overview>();
            var policy = new InterviewScoringPolicy();
            questionContracts.forEach((id, q) -> metadata.put(id, new InterviewQuestion.Overview(
                    q.competency(), q.importance(), q.difficulty(), policy.weightUnits(q.importance(), q.difficulty()), q.weightReason())));
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
        if (count < 1 || count > 10) throw new IllegalArgumentException("面试题数须为 1–10");
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
                InterviewQuestion question = agent.prepareQuestion(state.resume, role, List.of(), count, ai);
                String questionId = UUID.randomUUID().toString();
                state.questionContracts.put(questionId, question);
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
            if (state.turns.size() == state.count) {
                state.turns.set(last, answered); state.pending = null; state.status = "FINISHED";
            } else {
                List<Turn> history = new ArrayList<>(state.turns); history.set(last, answered);
                InterviewQuestion question = agent.prepareQuestion(state.resume, state.role, history, state.count, ai);
                String nextId = UUID.randomUUID().toString();
                state.turns.set(last, answered);
                state.questionContracts.put(nextId, question);
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
                inputs = turns.stream().filter(t -> t.answer() != null && !state.assessments.containsKey(t.questionId()))
                        .map(t -> new InterviewAgent.AssessmentInput(t, contracts.get(t.questionId()))).toList();
                if (inputs.isEmpty() && state.cachedReport != null) return state.cachedReport;
            }
            // Retain the report-specific lock for single-flight generation, but never block session reads on network IO.
            Map<String, InterviewAssessment> fresh = inputs.isEmpty() ? Map.of()
                    : agent.assessAnswers(state.resume, state.role, inputs, turns, client.get());
            find(owner, id);
            synchronized (state) {
                state.assessments.putAll(fresh);
                state.cachedReport = new InterviewReportAssembler().assemble(state.count, turns, contracts, state.assessments);
                return state.cachedReport;
            }
        }
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
