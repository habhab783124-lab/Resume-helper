package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import jakarta.annotation.PreDestroy;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Asynchronously assesses one closed QUICK question group without waiting on the request thread. */
public final class InterviewPracticeService implements AutoCloseable {
    private final SessionTaskRegistry tasks = new SessionTaskRegistry();
    private final InterviewService interviews;
    private final InterviewAgent agent;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "interview-practice-feedback");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public InterviewPracticeService(InterviewService interviews, InterviewAgent agent) {
        this.interviews = interviews;
        this.agent = agent;
    }

    public InterviewService.Session feedback(String owner, String sessionId, String questionId,
            String requestId, long expectedRevision, AiClient ai) {
        if (ai == null || ai.isFallback() || !ai.available())
            throw new InterviewException(503, "请配置模型后再查看题组反馈");
        var task = interviews.queuePracticeFeedback(owner, sessionId, questionId, requestId, expectedRevision);
        if (task.enqueue()) {
            try {
                tasks.submit(sessionId, executor, () -> {
                    if (!interviews.startPracticeFeedback(owner, sessionId, task.rootQuestionId(), task.requestId())) return;
                    try {
                        var context = interviews.practiceContext(owner, sessionId, task.rootQuestionId(), task.requestId());
                        var scores = agent.assessAnswers(context.resume(), context.role(),
                                java.util.List.of(context.input()), context.history(), ai);
                        interviews.completePracticeFeedback(owner, sessionId, task.rootQuestionId(), task.requestId(),
                                scores.get(task.rootQuestionId()));
                    } catch (Throwable failure) {
                        try { interviews.failPracticeFeedback(owner, sessionId, task.rootQuestionId(), task.requestId(),
                                "本题反馈暂不可用，可以继续下一题或稍后重试。"); }
                        catch (RuntimeException ignored) { /* Expired or deleted sessions stay absent. */ }
                    }
                });
            } catch (RuntimeException rejected) {
                interviews.failPracticeFeedback(owner, sessionId, task.rootQuestionId(), task.requestId(),
                        "反馈队列繁忙，可以直接继续下一题。");
            }
        }
        return interviews.get(owner, sessionId);
    }

    public InterviewAttempt submitAttempt(String owner, String sessionId, String questionId,
            String requestId, String answer, AiClient ai) {
        var task = interviews.submitAttempt(owner, sessionId, questionId, requestId, answer);
        scheduleAttempt(owner, sessionId, task, ai);
        return interviews.getAttempt(owner, sessionId, task.attempt().attemptId());
    }

    public InterviewAttempt getAttempt(String owner, String sessionId, String attemptId) {
        return interviews.getAttempt(owner, sessionId, attemptId);
    }

    public java.util.List<InterviewAttempt> listAttempts(String owner, String sessionId, String questionId) {
        return interviews.listAttempts(owner, sessionId, questionId);
    }

    public InterviewAttempt retryAttempt(String owner, String sessionId, String attemptId,
            String requestId, AiClient ai) {
        var task = interviews.retryAttempt(owner, sessionId, attemptId, requestId);
        scheduleAttempt(owner, sessionId, task, ai);
        return interviews.getAttempt(owner, sessionId, attemptId);
    }

    private void scheduleAttempt(String owner, String sessionId, InterviewService.AttemptTask task, AiClient ai) {
        if (!task.enqueue()) return;
        var attempt = task.attempt();
        if (ai == null || ai.isFallback() || !ai.available()) {
            interviews.failAttempt(owner, sessionId, attempt.attemptId(), attempt.operationId(),
                    "模型暂不可用；重答已保存，可配置模型后补评。");
            return;
        }
        try {
            tasks.submit(sessionId, executor, () -> {
                if (!interviews.startAttempt(owner, sessionId, attempt.attemptId(), attempt.operationId())) return;
                try {
                    var context = interviews.attemptContext(owner, sessionId, attempt.attemptId(), attempt.operationId());
                    var scores = agent.assessAnswers(context.resume(), context.role(),
                            java.util.List.of(context.input()), context.history(), ai);
                    interviews.completeAttempt(owner, sessionId, attempt.attemptId(), attempt.operationId(),
                            scores.get(attempt.questionId()));
                } catch (Throwable failure) {
                    try { interviews.failAttempt(owner, sessionId, attempt.attemptId(), attempt.operationId(),
                            "重答评分暂不可用，可补评；答案已保存。"); }
                    catch (RuntimeException ignored) { /* Expired or deleted sessions stay absent. */ }
                }
            });
        } catch (RuntimeException rejected) {
            interviews.failAttempt(owner, sessionId, attempt.attemptId(), attempt.operationId(),
                    "评分队列繁忙，可补评；答案已保存。");
        }
    }

    @PreDestroy @Override public void close() { executor.shutdownNow(); }

    void cancelSession(String sessionId) { tasks.cancel(sessionId); }
}
