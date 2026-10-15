package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

final class InterviewTestFixtures {
    private static final ObjectMapper JSON = new ObjectMapper();
    private InterviewTestFixtures() {}

    static String question(String text) { return question(text, "CORE", "INTERMEDIATE"); }

    static String question(String text, String importance, String difficulty) {
        return json(Map.of("question", text, "competency", "缓存可靠性", "importance", importance,
                "difficulty", difficulty, "weightReason", "岗位核心能力，需要验证实现与取舍",
                "criteria", List.of("CORRECTNESS", "COVERAGE", "REASONING", "EVIDENCE", "CLARITY")
                        .stream().map(d -> Map.of("dimension", d, "expectation", "结合本题说明真实实现、依据与局限")).toList()));
    }

    static Map<String, Object> assessment(String id, String answer, int score) {
        var dimensions = new LinkedHashMap<String, Integer>();
        int remaining = score;
        for (String key : List.of("CORRECTNESS", "COVERAGE", "REASONING", "EVIDENCE", "CLARITY")) {
            dimensions.put(key, Math.min(20, remaining));
            remaining -= Math.min(20, remaining);
        }
        return new LinkedHashMap<>(Map.of("questionId", id, "dimensionScores", dimensions,
                "evidence", List.of(answer), "missingPoints", List.of("未说明统计周期"),
                "highlight", "说明了个人采取的具体动作", "improvement", "补充真实统计周期与样本范围",
                "suggestedOutline", "先说明个人动作，再补充真实测量依据与局限"));
    }

    static String assessmentsFromPrompt(String prompt, int score) {
        try {
            var root = JSON.readTree(prompt);
            var entries = new ArrayList<Map<String, Object>>();
            for (var input : root.path("inputs")) {
                var turn = input.path("turn");
                entries.add(assessment(turn.path("questionId").asText(), turn.path("answer").asText(), score));
            }
            return json(Map.of("assessments", entries));
        } catch (Exception e) { throw new AssertionError(e); }
    }

    static InterviewQuestion contract(String importance, String difficulty) {
        return new InterviewQuestion("问题", "缓存能力", InterviewQuestion.Importance.valueOf(importance),
                InterviewQuestion.Difficulty.valueOf(difficulty), "岗位相关能力",
                Arrays.stream(InterviewQuestion.Dimension.values()).map(d ->
                        new InterviewQuestion.Criterion(d, "说明本题的关键事实和依据")).toList(), InterviewScoringPolicy.VERSION);
    }

    static InterviewAssessment assessed(String id, int score) {
        var dimensions = new EnumMap<InterviewQuestion.Dimension, Integer>(InterviewQuestion.Dimension.class);
        int remaining = score;
        for (var d : InterviewQuestion.Dimension.values()) {
            int value = Math.min(20, remaining);
            dimensions.put(d, value);
            remaining -= value;
        }
        return new InterviewAssessment(id, dimensions, List.of("我的回答"), List.of("缺少测量周期"),
                "说明了具体动作", "补充真实测量周期", "动作、证据与结果", "test-model");
    }

    static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
