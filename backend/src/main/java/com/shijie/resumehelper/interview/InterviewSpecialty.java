package com.shijie.resumehelper.interview;

import java.util.Objects;

/** Validated with the opening question and frozen for the session. */
public record InterviewSpecialty(Kind kind, String focus, String reason) {
    public enum Kind { ALGORITHM, CASE_STUDY }
    public InterviewSpecialty {
        Objects.requireNonNull(kind, "专项类型不能为空");
        focus = focus == null ? "" : focus.strip();
        reason = reason == null ? "" : reason.strip();
        InterviewQuestion.requireText(focus, 200, "specialty.focus");
        InterviewQuestion.requireText(reason, 500, "specialty.reason");
    }
}
