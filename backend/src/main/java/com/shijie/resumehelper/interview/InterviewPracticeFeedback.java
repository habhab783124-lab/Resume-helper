package com.shijie.resumehelper.interview;

import java.util.Objects;

/** Durable state for one QUICK root group's teaching feedback. */
public record InterviewPracticeFeedback(String requestId, Status status,
        InterviewAssessment assessment, String error) {
    public enum Status { QUEUED, RUNNING, COMPLETE, FAILED }
    public InterviewPracticeFeedback {
        if (requestId == null || requestId.isBlank() || requestId.length() > 120)
            throw new IllegalArgumentException("反馈请求 ID 无效");
        Objects.requireNonNull(status);
        error = error == null ? "" : error;
        if (status == Status.COMPLETE && assessment == null)
            throw new IllegalArgumentException("完成的反馈必须包含评分依据");
        if (status != Status.COMPLETE && assessment != null)
            throw new IllegalArgumentException("未完成的反馈不能包含评分结果");
    }
}
