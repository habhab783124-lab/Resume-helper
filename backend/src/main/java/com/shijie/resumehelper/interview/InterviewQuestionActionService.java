package com.shijie.resumehelper.interview;

import java.util.Objects;

/** Question-level actions whose operation state and race checks are owned by InterviewService. */
public final class InterviewQuestionActionService {
    private final InterviewService interviews;
    public InterviewQuestionActionService(InterviewService interviews) {
        this.interviews = Objects.requireNonNull(interviews);
    }
    public InterviewService.Session skip(String owner, String sessionId, String questionId,
                                         String requestId, long expectedRevision) {
        return interviews.closeRoot(owner, sessionId, questionId, requestId, expectedRevision);
    }
    public InterviewService.Session clarify(String owner, String sessionId, String questionId,
            String requestId, long expectedRevision) {
        return interviews.beginClarification(owner, sessionId, questionId, requestId, expectedRevision);
    }
    public InterviewService.Session replace(String owner, String sessionId, String questionId,
            String requestId, long expectedRevision, String reason) {
        return interviews.beginReplacement(owner, sessionId, questionId, requestId, expectedRevision, reason);
    }
}
