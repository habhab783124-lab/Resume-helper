package com.shijie.resumehelper.interview;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Evidence-anchored, independently versioned interview review highlights. */
public record InterviewReviewSummary(Status status, long sourceVersion, List<PriorityIssue> priorityIssues, String error) {
    public InterviewReviewSummary {
        Objects.requireNonNull(status);
        priorityIssues = priorityIssues == null ? List.of() : List.copyOf(priorityIssues);
        error = error == null ? "" : error;
    }
    public enum Status { PENDING, COMPLETE, UNAVAILABLE }
    public enum Kind { CORRECTNESS, COVERAGE, REASONING, EVIDENCE, CLARITY }
    public record PriorityIssue(String questionId, Kind kind, String evidenceQuote, String issue, String suggestedAction) {}
    public record ValidationContext(Map<String, String> answers, Set<String> scoredQuestionIds) {
        public ValidationContext {
            answers = Map.copyOf(answers);
            scoredQuestionIds = Set.copyOf(scoredQuestionIds);
        }
    }

    public static List<PriorityIssue> validate(List<PriorityIssue> issues, ValidationContext context) {
        if (issues == null || issues.size() > 3) throw new IllegalArgumentException("重点问题最多三条");
        Set<String> seen = new HashSet<>();
        for (PriorityIssue issue : issues) {
            if (issue == null || issue.questionId() == null || !context.scoredQuestionIds().contains(issue.questionId()))
                throw new IllegalArgumentException("重点引用了未评分或不存在的问题");
            if (!seen.add(issue.questionId())) throw new IllegalArgumentException("同一问题不能重复归纳");
            String answer = context.answers().get(issue.questionId());
            if (answer == null || issue.evidenceQuote() == null || issue.evidenceQuote().isBlank()
                    || issue.evidenceQuote().length() > 300 || !answer.contains(issue.evidenceQuote()))
                throw new IllegalArgumentException("重点证据必须逐字出现在对应回答中");
            InterviewQuestion.requireText(issue.issue(), 240, "问题重点");
            InterviewQuestion.requireText(issue.suggestedAction(), 300, "建议动作");
            if (issue.kind() == null) throw new IllegalArgumentException("重点类型无效");
        }
        return List.copyOf(issues);
    }
}
