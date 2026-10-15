package com.shijie.resumehelper.interview;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Builds canonical reports without asking the model to perform arithmetic or invent fallback scores. */
public final class InterviewReportAssembler {
    private final InterviewScoringPolicy policy = new InterviewScoringPolicy();

    public InterviewAgent.InterviewReport assemble(int plannedCount, List<InterviewService.Turn> turns,
            Map<String, InterviewQuestion> contracts, Map<String, InterviewAssessment> assessments) {
        List<InterviewService.Turn> answered = turns.stream().filter(t -> t.answer() != null).toList();
        List<Integer> weights = answered.stream().map(t -> weight(contracts.get(t.questionId()))).toList();
        List<BigDecimal> percentages = weights.isEmpty() ? List.of() : policy.percentages(weights);
        Map<String, BigDecimal> shares = new HashMap<>();
        for (int i = 0; i < answered.size(); i++) shares.put(answered.get(i).questionId(), percentages.get(i));
        int totalWeight = weights.stream().mapToInt(Integer::intValue).sum();
        List<InterviewAgent.TurnFeedback> feedbacks = new ArrayList<>();
        List<Integer> scored = new ArrayList<>();
        Set<String> models = new LinkedHashSet<>();
        for (int i = 0; i < turns.size(); i++) {
            var turn = turns.get(i);
            var contract = Objects.requireNonNull(contracts.get(turn.questionId()), "题目缺少冻结的评分契约");
            var assessment = turn.answer() == null ? null : assessments.get(turn.questionId());
            if (assessment != null && !assessment.questionId().equals(turn.questionId()))
                throw new IllegalArgumentException("评分与题目 ID 不匹配");
            Integer score = assessment == null ? null : policy.questionScore(assessment.dimensionScores());
            if (score != null) { scored.add(score); models.add(assessment.aiModel()); }
            BigDecimal contribution = score == null ? null : BigDecimal.valueOf((long) score * weight(contract))
                    .divide(BigDecimal.valueOf(totalWeight), 4, RoundingMode.HALF_UP);
            feedbacks.add(new InterviewAgent.TurnFeedback(i + 1, turn.question(), score,
                    assessment == null ? "" : assessment.highlight(), assessment == null ? "" : assessment.improvement(),
                    turn.questionId(), turn.answer(), turn.answer() == null ? "UNANSWERED" : score == null ? "PENDING" : "SCORED",
                    contract.competency(), contract.difficulty().name(), contract.importance().name(),
                    weight(contract), shares.get(turn.questionId()), contribution, contract.weightReason(),
                    assessment == null ? Map.of() : assessment.dimensionScores(),
                    assessment == null ? List.of() : assessment.evidence(),
                    assessment == null ? List.of() : assessment.missingPoints(),
                    assessment == null ? "" : assessment.suggestedOutline(), assessment == null ? "" : assessment.aiModel()));
        }
        String status = answered.isEmpty() ? "EMPTY" : scored.isEmpty() ? "UNAVAILABLE"
                : scored.size() == answered.size() ? "COMPLETE" : "PARTIAL";
        BigDecimal overall = status.equals("COMPLETE") ? policy.overallScore(scored, weights) : null;
        String scope = (answered.size() < plannedCount ? "本次提前结束，" : "本次")
                + "已答 " + answered.size() + "/" + plannedCount + " 题，已评分 " + scored.size() + " 题。";
        String summary = scope + (overall != null ? "加权训练参考评分 " + overall + "，仅反映本轮已回答内容。"
                : answered.isEmpty() ? "还没有回答，暂无可评分内容。" : "评分暂未齐全，可重试待评分题目；暂无综合评分。");
        List<String> strengths = feedbacks.stream().filter(f -> f.score() != null).map(InterviewAgent.TurnFeedback::highlight)
                .filter(s -> !s.isBlank()).distinct().limit(5).toList();
        List<String> gaps = feedbacks.stream().flatMap(f -> f.missingPoints().stream()).distinct().limit(5).toList();
        List<String> suggestions = feedbacks.stream().filter(f -> f.score() != null)
                .sorted(Comparator.comparingInt((InterviewAgent.TurnFeedback f) -> f.weightUnits() * (100 - f.score())).reversed())
                .map(InterviewAgent.TurnFeedback::improvement).filter(s -> !s.isBlank()).distinct().limit(3).toList();
        return new InterviewAgent.InterviewReport(overall, summary, strengths, gaps, suggestions, feedbacks,
                String.join(", ", models), status, InterviewScoringPolicy.VERSION, plannedCount, answered.size(), scored.size());
    }

    private int weight(InterviewQuestion contract) {
        Objects.requireNonNull(contract, "题目缺少冻结的评分契约");
        return policy.weightUnits(contract.importance(), contract.difficulty());
    }
}
