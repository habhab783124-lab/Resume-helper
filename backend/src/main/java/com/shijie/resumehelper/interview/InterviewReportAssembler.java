package com.shijie.resumehelper.interview;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Builds canonical reports without asking the model to perform arithmetic or invent fallback scores. */
public final class InterviewReportAssembler {
    private final InterviewScoringPolicy policy = new InterviewScoringPolicy();

    public InterviewAgent.InterviewReport assemble(int plannedCount, List<InterviewService.Turn> turns,
            Map<String, InterviewQuestion> contracts, Map<String, InterviewAssessment> assessments) {
        Map<String, InterviewService.Context> contexts = new LinkedHashMap<>();
        for (int i = 0; i < turns.size(); i++) contexts.put(turns.get(i).questionId(),
                new InterviewService.Context(InterviewService.Kind.MAIN, null, i + 1, null));
        return assemble(plannedCount, turns, contracts, assessments, contexts);
    }

    public InterviewAgent.InterviewReport assemble(int plannedCount, List<InterviewService.Turn> allTurns,
            Map<String, InterviewQuestion> contracts, Map<String, InterviewAssessment> assessments,
            Map<String, InterviewService.Context> contexts) {
        var turns = allTurns.stream().filter(t -> contexts.get(t.questionId()).kind() != InterviewService.Kind.FOLLOW_UP).toList();
        List<InterviewService.Turn> answered = turns.stream().filter(t -> t.answer() != null).toList();
        var shares = InterviewService.weightShares(allTurns, contracts, contexts);
        List<InterviewAgent.TurnFeedback> feedbacks = new ArrayList<>();
        List<Integer> scored = new ArrayList<>();
        Set<String> models = new LinkedHashSet<>();
        long mainUnits = turns.stream().filter(t -> contexts.get(t.questionId()).kind() == InterviewService.Kind.MAIN)
                .mapToLong(t -> weight(contracts.get(t.questionId()))).sum();
        for (int i = 0; i < turns.size(); i++) {
            var turn = turns.get(i);
            var contract = Objects.requireNonNull(contracts.get(turn.questionId()), "题目缺少冻结的评分契约");
            var assessment = turn.answer() == null ? null : assessments.get(turn.questionId());
            if (assessment != null && !assessment.questionId().equals(turn.questionId()))
                throw new IllegalArgumentException("评分与题目 ID 不匹配");
            Integer score = assessment == null ? null : policy.questionScore(assessment.dimensionScores());
            if (score != null) { scored.add(score); models.add(assessment.aiModel()); }
            var context = contexts.get(turn.questionId());
            BigDecimal contribution = score == null ? null : context.kind() == InterviewService.Kind.ALGORITHM
                    ? BigDecimal.valueOf(score).multiply(new BigDecimal("0.2"))
                    : BigDecimal.valueOf((long) score * weight(contract) * 4)
                        .divide(BigDecimal.valueOf(mainUnits * 5), 10, RoundingMode.HALF_UP);
            feedbacks.add(new InterviewAgent.TurnFeedback(i + 1, turn.question(), score,
                    assessment == null ? "" : assessment.highlight(), assessment == null ? "" : assessment.improvement(),
                    turn.questionId(), turn.answer(), turn.answer() == null ? "UNANSWERED" : score == null ? "PENDING" : "SCORED",
                    contract.competency(), contract.difficulty().name(), contract.importance().name(),
                    weight(contract), shares.get(turn.questionId()), contribution, contract.weightReason(),
                    assessment == null ? Map.of() : assessment.dimensionScores(),
                    assessment == null ? List.of() : assessment.evidence(),
                    assessment == null ? List.of() : assessment.missingPoints(),
                    assessment == null ? "" : assessment.suggestedOutline(), assessment == null ? "" : assessment.aiModel(),
                    context.kind(), null, context.mainQuestionIndex(), context.algorithm(),
                    allTurns.stream().filter(f -> turn.questionId().equals(contexts.get(f.questionId()).parentQuestionId())).toList()));
        }
        String status = answered.isEmpty() ? "EMPTY" : scored.isEmpty() ? "UNAVAILABLE"
                : scored.size() == answered.size() ? "COMPLETE" : "PARTIAL";
        boolean fullyCovered = plannedCount == InterviewService.QUESTION_COUNT && answered.size() == InterviewService.QUESTION_COUNT
                && turns.stream().filter(t -> contexts.get(t.questionId()).kind() == InterviewService.Kind.MAIN).count() == InterviewService.MAIN_COUNT
                && turns.stream().filter(t -> contexts.get(t.questionId()).kind() == InterviewService.Kind.ALGORITHM).count() == 1;
        BigDecimal overall = status.equals("COMPLETE") && fullyCovered
                ? fullScore(feedbacks) : null;
        String scope = (answered.size() < plannedCount ? "本次提前结束，" : "本次")
                + "已答 " + answered.size() + "/" + plannedCount + " 题，已评分 " + scored.size() + " 题。";
        long answeredMain = answered.stream().filter(t -> contexts.get(t.questionId()).kind() == InterviewService.Kind.MAIN).count();
        boolean answeredAlgorithm = answered.stream().anyMatch(t -> contexts.get(t.questionId()).kind() == InterviewService.Kind.ALGORITHM);
        if (!fullyCovered) scope += "未完成主问题 " + Math.max(0, InterviewService.MAIN_COUNT - answeredMain)
                + " 道；" + (answeredAlgorithm ? "算法题已回答。" : "算法题未作答（固定20%未计入，不转移给主问题）。");
        String summary = scope + (overall != null ? "加权训练参考评分 " + overall + "，仅反映本轮已回答内容。"
                : answered.isEmpty() ? "还没有回答，暂无可评分内容。" : status.equals("COMPLETE") ? "已答题目的逐题反馈已完成；范围未齐全，暂无综合评分。" : "评分暂未齐全，可重试待评分题目；暂无综合评分。");
        List<String> strengths = feedbacks.stream().filter(f -> f.score() != null).map(InterviewAgent.TurnFeedback::highlight)
                .filter(s -> !s.isBlank()).distinct().limit(5).toList();
        List<String> gaps = feedbacks.stream().flatMap(f -> f.missingPoints().stream()).distinct().limit(5).toList();
        List<String> suggestions = feedbacks.stream().filter(f -> f.score() != null)
                .sorted(Comparator.comparing((InterviewAgent.TurnFeedback f) ->
                        f.weightPercent().multiply(BigDecimal.valueOf(100 - f.score()))).reversed())
                .map(InterviewAgent.TurnFeedback::improvement).filter(s -> !s.isBlank()).distinct().limit(3).toList();
        return new InterviewAgent.InterviewReport(overall, summary, strengths, gaps, suggestions, feedbacks,
                String.join(", ", models), status, InterviewScoringPolicy.VERSION, plannedCount, answered.size(), scored.size());
    }

    private int weight(InterviewQuestion contract) {
        Objects.requireNonNull(contract, "题目缺少冻结的评分契约");
        return policy.weightUnits(contract.importance(), contract.difficulty());
    }

    private BigDecimal fullScore(List<InterviewAgent.TurnFeedback> feedbacks) {
        long units = 0, weighted = 0;
        int algorithmScore = 0;
        for (var row : feedbacks) {
            if (row.kind() == InterviewService.Kind.ALGORITHM) algorithmScore = row.score();
            else { units += row.weightUnits(); weighted += (long) row.score() * row.weightUnits(); }
        }
        // One final rounding step; display percentages must never feed score arithmetic.
        return BigDecimal.valueOf(weighted * 4 + (long) algorithmScore * units)
                .divide(BigDecimal.valueOf(units * 5), 1, RoundingMode.HALF_UP);
    }
}
