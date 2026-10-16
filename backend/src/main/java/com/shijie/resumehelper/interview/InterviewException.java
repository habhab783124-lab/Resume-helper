package com.shijie.resumehelper.interview;

/** A safe, user-facing error; never include upstream payloads or credentials. */
public final class InterviewException extends RuntimeException {
    private final int status;
    private final InterviewFailure failure;

    public InterviewException(int status, String message) {
        super(message);
        this.status = status;
        this.failure = null;
    }

    public InterviewException(int status, String message, InterviewFailure failure) {
        super(message);
        this.status = status;
        this.failure = failure;
    }

    public int status() {
        return status;
    }

    /** 面向前端的可追踪失败记录，未提供时为 {@code null}（仅无 code 的人类可读错误）。 */
    public InterviewFailure failure() {
        return failure;
    }
}
