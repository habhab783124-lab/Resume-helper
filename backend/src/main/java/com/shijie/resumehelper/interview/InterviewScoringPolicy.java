package com.shijie.resumehelper.interview;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Deterministic arithmetic for training scores, independent of model output. */
public final class InterviewScoringPolicy {
    public static final String VERSION = "weighted-evidence-v1";

    public int weightUnits(InterviewQuestion.Importance importance, InterviewQuestion.Difficulty difficulty) {
        if (importance == null || difficulty == null)
            throw new IllegalArgumentException("题目分类不完整");
        int base = switch (importance) { case CORE -> 3; case RELATED -> 2; case SUPPLEMENT -> 1; };
        int factor = switch (difficulty) { case BASIC -> 4; case INTERMEDIATE -> 5; case ADVANCED -> 6; };
        return base * factor;
    }

    public int questionScore(Map<InterviewQuestion.Dimension, Integer> dimensions) {
        if (dimensions == null || dimensions.size() != InterviewQuestion.Dimension.values().length)
            throw new IllegalArgumentException("评分维度不完整");
        int result = 0;
        for (var dimension : InterviewQuestion.Dimension.values()) {
            Integer value = dimensions.get(dimension);
            if (value == null || value < 0 || value > 20 || value % 5 != 0)
                throw new IllegalArgumentException("维度分数无效");
            result += value;
        }
        return result;
    }

    public BigDecimal overallScore(List<Integer> scores, List<Integer> weights) {
        if (scores == null || weights == null || scores.isEmpty() || scores.size() != weights.size())
            throw new IllegalArgumentException("评分与权重不完整");
        long numerator = 0, denominator = 0;
        for (int i = 0; i < scores.size(); i++) {
            Integer score = scores.get(i), weight = weights.get(i);
            if (score == null || score < 0 || score > 100 || weight == null || weight <= 0)
                throw new IllegalArgumentException("评分或权重无效");
            numerator += (long) score * weight;
            denominator += weight;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
    }

    /** Largest remainders allocate exactly 10,000 basis points; ties retain question order. */
    public List<BigDecimal> percentages(List<Integer> weights) {
        if (weights == null || weights.isEmpty()) throw new IllegalArgumentException("权重不能为空");
        long total = 0;
        for (Integer weight : weights) {
            if (weight == null || weight <= 0) throw new IllegalArgumentException("权重须为正整数");
            total += weight;
        }
        long[] basisPoints = new long[weights.size()];
        long[] remainders = new long[weights.size()];
        List<Integer> order = new ArrayList<>(weights.size());
        long allocated = 0;
        for (int i = 0; i < weights.size(); i++) {
            long scaled = (long) weights.get(i) * 10_000;
            basisPoints[i] = scaled / total;
            remainders[i] = scaled % total;
            allocated += basisPoints[i];
            order.add(i);
        }
        order.sort(Comparator.<Integer>comparingLong(i -> remainders[i]).reversed()
                .thenComparingInt(Integer::intValue));
        for (int i = 0; i < 10_000 - allocated; i++) basisPoints[order.get(i)]++;
        List<BigDecimal> result = new ArrayList<>(weights.size());
        for (long value : basisPoints) result.add(BigDecimal.valueOf(value, 2));
        return List.copyOf(result);
    }
}
