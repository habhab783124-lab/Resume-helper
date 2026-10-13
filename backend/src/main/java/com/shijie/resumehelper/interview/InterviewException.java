package com.shijie.resumehelper.interview;

/** A safe, user-facing error; never include upstream payloads or credentials. */
public final class InterviewException extends RuntimeException {
    private final int status;
    public InterviewException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}
