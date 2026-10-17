package com.shijie.resumehelper.interview;

import java.util.Objects;

/** One independent candidate answer; it never replaces the original Turn. */
public record InterviewAttempt(String attemptId, String requestId, String operationId, String questionId, int version,
        String answer, Status status, InterviewAssessment feedback, InterviewAttemptComparison comparison,
        String model, String rubricVersion, String error) {
    public enum Status { PENDING, RUNNING, COMPLETE, FAILED }
    public InterviewAttempt {
        if (attemptId == null || attemptId.isBlank() || requestId == null || requestId.isBlank()
                || operationId == null || operationId.isBlank()
                || questionId == null || questionId.isBlank()) throw new IllegalArgumentException("重答标识不能为空");
        if (version < 1 || version > 5) throw new IllegalArgumentException("每题最多保存五版重答");
        if (answer == null || answer.isBlank() || answer.strip().length() > 5000)
            throw new IllegalArgumentException("重答须为 1–5000 字");
        answer = answer.strip();
        Objects.requireNonNull(status);
        model = model == null ? "" : model;
        rubricVersion = rubricVersion == null ? "" : rubricVersion;
        error = error == null ? "" : error;
        if ((status == Status.COMPLETE) != (feedback != null && comparison != null))
            throw new IllegalArgumentException("已完成重答必须包含评分与对比");
    }
}
