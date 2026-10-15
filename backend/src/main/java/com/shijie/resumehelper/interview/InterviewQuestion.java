package com.shijie.resumehelper.interview;

import java.util.EnumSet;
import java.util.List;

/** Immutable scoring contract frozen before the candidate sees a question. */
public record InterviewQuestion(
        String text, String competency, Importance importance, Difficulty difficulty,
        String weightReason, List<Criterion> criteria, String rubricVersion) {
    public enum Importance { CORE, RELATED, SUPPLEMENT }
    public enum Difficulty { BASIC, INTERMEDIATE, ADVANCED }
    public enum Dimension { CORRECTNESS, COVERAGE, REASONING, EVIDENCE, CLARITY }

    public InterviewQuestion {
        requireText(text, 500, "问题正文");
        requireText(competency, 200, "考察能力");
        requireText(weightReason, 200, "权重原因");
        if (importance == null || difficulty == null)
            throw new IllegalArgumentException("题目分类不完整");
        if (rubricVersion == null || rubricVersion.isBlank())
            throw new IllegalArgumentException("评分策略版本不能为空");
        if (criteria == null || criteria.size() != Dimension.values().length)
            throw new IllegalArgumentException("评分标准须覆盖五个维度");
        EnumSet<Dimension> dimensions = EnumSet.noneOf(Dimension.class);
        for (Criterion criterion : criteria) {
            if (criterion == null || !dimensions.add(criterion.dimension()))
                throw new IllegalArgumentException("评分标准维度缺失或重复");
        }
        criteria = List.copyOf(criteria);
    }

    public record Criterion(Dimension dimension, String expectation) {
        public Criterion {
            if (dimension == null) throw new IllegalArgumentException("评分维度不能为空");
            requireText(expectation, 500, "评分标准");
        }
    }

    /** Safe question metadata; the frozen answer criteria are deliberately absent. */
    public record Overview(String competency, Importance importance,
                           Difficulty difficulty, int weightUnits, String weightReason) {}

    static void requireText(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum)
            throw new IllegalArgumentException(field + "须为 1–" + maximum + " 字");
    }
}
