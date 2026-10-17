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
        return assemble(plannedCount, allTurns, contracts, assessments, contexts, "");
    }

    public InterviewAgent.InterviewReport assemble(int plannedCount, List<InterviewService.Turn> allTurns,
            Map<String, InterviewQuestion> contracts, Map<String, InterviewAssessment> assessments,
            Map<String, InterviewService.Context> contexts, String finishReason) {
        return assemble(plannedCount, allTurns, contracts, assessments, contexts, finishReason, null,
                InterviewSnapshot.Progress.empty());
    }

    public InterviewAgent.InterviewReport assemble(int plannedCount, List<InterviewService.Turn> allTurns,
            Map<String, InterviewQuestion> contracts, Map<String, InterviewAssessment> assessments,
            Map<String, InterviewService.Context> contexts, String finishReason, InterviewPlan plan,
            InterviewSnapshot.Progress progress) {
        return assemble(plannedCount, allTurns, contracts, assessments, contexts, finishReason, plan, progress, Set.of());
    }

    public InterviewAgent.InterviewReport assemble(int plannedCount, List<InterviewService.Turn> allTurns,
            Map<String, InterviewQuestion> contracts, Map<String, InterviewAssessment> assessments,
            Map<String, InterviewService.Context> contexts, String finishReason, InterviewPlan plan,
            InterviewSnapshot.Progress progress, Set<String> skippedRoots) {
        return assemble(plannedCount, allTurns, contracts, assessments, contexts, finishReason, plan, progress,
                skippedRoots, Set.of());
    }

    public InterviewAgent.InterviewReport assemble(int plannedCount, List<InterviewService.Turn> allTurns,
            Map<String, InterviewQuestion> contracts, Map<String, InterviewAssessment> assessments,
            Map<String, InterviewService.Context> contexts, String finishReason, InterviewPlan plan,
            InterviewSnapshot.Progress progress, Set<String> skippedRoots, Set<String> withdrawnQuestionIds) {
        var turns = allTurns.stream().filter(t -> contexts.get(t.questionId()).kind() != InterviewService.Kind.FOLLOW_UP)
                .filter(t -> !withdrawnQuestionIds.contains(t.questionId())).toList();
        List<InterviewService.Turn> answered = turns.stream().filter(t -> t.answer() != null).toList();
        var shares = InterviewService.weightShares(allTurns.stream()
                .filter(t -> !withdrawnQuestionIds.contains(t.questionId())).toList(), contracts, contexts);
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
            BigDecimal contribution = score == null ? null : context.kind().isSpecialty()
                    ? BigDecimal.valueOf(score).multiply(new BigDecimal("0.2"))
                    : BigDecimal.valueOf((long) score * weight(contract) * 4)
                        .divide(BigDecimal.valueOf(mainUnits * 5), 10, RoundingMode.HALF_UP);
            feedbacks.add(new InterviewAgent.TurnFeedback(i + 1, turn.question(), score,
                    assessment == null ? "" : assessment.highlight(), assessment == null ? "" : assessment.improvement(),
                    turn.questionId(), turn.answer(), turn.answer() == null
                            ? (skippedRoots.contains(turn.questionId()) ? "SKIPPED" : "UNANSWERED")
                            : score == null ? "PENDING" : "SCORED",
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
        boolean quick = plan != null && "experience-v1".equals(plan.policyVersion())
                && plan.mode() == InterviewPlan.Mode.QUICK;
        int plannedMainCount = plan != null && "experience-v1".equals(plan.policyVersion())
                ? plan.mainCount() : InterviewService.MAIN_COUNT;
        boolean expectsSpecialty = plan == null || plan.plannedCount() > plan.mainCount();
        long actualMainCount = turns.stream().filter(t -> contexts.get(t.questionId()).kind() == InterviewService.Kind.MAIN).count();
        long actualSpecialtyCount = turns.stream().filter(t -> contexts.get(t.questionId()).kind().isSpecialty()).count();
        long answeredMainCount = answered.stream().filter(t -> contexts.get(t.questionId()).kind() == InterviewService.Kind.MAIN).count();
        long answeredSpecialtyCount = answered.stream().filter(t -> contexts.get(t.questionId()).kind().isSpecialty()).count();
        boolean fullyCovered = !quick && answered.size() == plannedCount
                && actualMainCount == plannedMainCount && answeredMainCount == plannedMainCount
                && actualSpecialtyCount == (expectsSpecialty ? 1 : 0)
                && answeredSpecialtyCount == (expectsSpecialty ? 1 : 0);
        BigDecimal overall = status.equals("COMPLETE") && fullyCovered
                ? fullScore(feedbacks) : null;
        boolean completed = "COMPLETED".equals(finishReason);
        String scope = (answered.size() < plannedCount && !completed ? "本次提前结束，" : "本次")
                + "已答 " + answered.size() + "/" + plannedCount + " 题，已评分 " + scored.size() + " 题。";
        boolean answeredSpecialty = answered.stream().anyMatch(t -> contexts.get(t.questionId()).kind().isSpecialty());
        int skippedCount = progress == null ? skippedRoots.size() : progress.skippedRoots();
        if (skippedCount > 0 && !quick) scope += "已跳过 " + skippedCount + " 个题组；跳过题与尚未到达的题目分别标示。";
        if (quick) {
            scope += completed ? "快速练习已按计划结束；" : "快速练习；";
            if (skippedCount > 0) scope += "已跳过 " + skippedCount + " 个题组；";
            scope += "该模式不计算综合评分。";
        } else if (!fullyCovered) {
            int missingMain = (int) Math.max(0, plannedMainCount - answeredMainCount);
            if (missingMain > 0) scope += "未完成主问题 " + missingMain + " 道；";
            if (expectsSpecialty && !answeredSpecialty)
                scope += "专项题未作答（固定20%未计入，不转移给主问题）。";
            else if (completed) scope += "本轮已结束，但答题范围未齐全，暂无综合评分。";
        }
        String summary = scope + (overall != null ? "加权训练参考评分 " + overall + "，仅反映本轮已回答内容。"
                : quick ? (answered.isEmpty() ? "还没有回答，暂无逐题反馈。" : "已答题目的逐题反馈已完成。")
                : answered.isEmpty() ? "还没有回答，暂无可评分内容。" : status.equals("COMPLETE") ? "已答题目的逐题反馈已完成；范围未齐全，暂无综合评分。" : "评分暂未齐全，可重试待评分题目；暂无综合评分。");
        List<String> strengths = feedbacks.stream().filter(f -> f.score() != null).map(InterviewAgent.TurnFeedback::highlight)
                .filter(s -> !s.isBlank()).distinct().limit(5).toList();
        List<String> gaps = feedbacks.stream().flatMap(f -> f.missingPoints().stream()).distinct().limit(5).toList();
        List<String> suggestions = feedbacks.stream().filter(f -> f.score() != null)
                .sorted(Comparator.comparing((InterviewAgent.TurnFeedback f) ->
                        f.weightPercent().multiply(BigDecimal.valueOf(100 - f.score()))).reversed())
                .map(InterviewAgent.TurnFeedback::improvement).filter(s -> !s.isBlank()).distinct().limit(3).toList();
        return new InterviewAgent.InterviewReport(overall, summary, strengths, gaps, suggestions, feedbacks,
                String.join(", ", models), status, InterviewScoringPolicy.VERSION, plannedCount, answered.size(), scored.size(), finishReason);
    }

    private int weight(InterviewQuestion contract) {
        Objects.requireNonNull(contract, "题目缺少冻结的评分契约");
        return policy.weightUnits(contract.importance(), contract.difficulty());
    }

    private BigDecimal fullScore(List<InterviewAgent.TurnFeedback> feedbacks) {
        long units = 0, weighted = 0;
        int specialtyScore = 0;
        for (var row : feedbacks) {
            if (row.kind().isSpecialty()) specialtyScore = row.score();
            else { units += row.weightUnits(); weighted += (long) row.score() * row.weightUnits(); }
        }
        // One final rounding step; display percentages must never feed score arithmetic.
        return BigDecimal.valueOf(weighted * 4 + (long) specialtyScore * units)
                .divide(BigDecimal.valueOf(units * 5), 1, RoundingMode.HALF_UP);
    }
}
