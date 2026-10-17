package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Bounded background executor for report scoring. Credentials and clients exist only in queued work. */
@Component
public final class InterviewReportRunner {
    private final SessionTaskRegistry tasks = new SessionTaskRegistry();
    private final InterviewService interviews;
    private final InterviewAgent agent;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "interview-report-scoring");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @org.springframework.beans.factory.annotation.Autowired
    public InterviewReportRunner(InterviewService interviews, InterviewAgent agent) { this.interviews = interviews; this.agent = agent; }
    public InterviewReportRunner(InterviewService interviews) { this(interviews, new InterviewAgent()); }

    public InterviewAgent.InterviewReport enqueue(String owner, String sessionId, String requestId,
            Supplier<AiClient> client, boolean retry) {
        var task = interviews.queueReport(owner, sessionId, requestId, retry);
        if (task.enqueue()) {
            try {
                AiClient ai = client.get();
                tasks.submit(sessionId, executor, () -> run(owner, sessionId, task.operationId(), ai));
            } catch (RuntimeException rejected) {
                interviews.failReportScoring(owner, sessionId, task.operationId(),
                        new IllegalStateException("评分队列已满，请稍后重试。", rejected));
            }
        }
        var report = interviews.reportSnapshot(owner, sessionId);
        if ("SUCCEEDED".equals(report.scoringState())) enqueueReview(owner, sessionId,
                "review-get-" + sessionId, client, false);
        return interviews.reportSnapshot(owner, sessionId);
    }

    public InterviewAgent.InterviewReport retryReview(String owner, String sessionId, String requestId,
            Supplier<AiClient> client) {
        enqueueReview(owner, sessionId, requestId, client, true);
        return interviews.reportSnapshot(owner, sessionId);
    }

    private void enqueueReview(String owner, String sessionId, String requestId, Supplier<AiClient> client, boolean retry) {
        var task = interviews.queueReview(owner, sessionId, requestId, retry);
        if (!task.enqueue()) return;
        try {
            AiClient ai = client.get();
            tasks.submit(sessionId, executor, () -> runReview(owner, sessionId, task.operationId(), ai));
        } catch (RuntimeException rejected) {
            interviews.failReview(owner, sessionId, task.operationId(), "归纳队列繁忙，请重试。");
        }
    }

    private void runReview(String owner, String sessionId, String operationId, AiClient ai) {
        if (!interviews.startReview(owner, sessionId, operationId)) return;
        try {
            var context = interviews.reviewContext(owner, sessionId, operationId);
            var issues = agent.summarizeReview(context.answers(), context.feedback(), context.scoredQuestionIds(), ai);
            interviews.completeReview(owner, sessionId, operationId, issues);
        } catch (Throwable cause) {
            try { interviews.failReview(owner, sessionId, operationId, "报告重点归纳暂不可用，可重试。"); }
            catch (RuntimeException ignored) { /* Expired or deleted sessions stay absent. */ }
        }
    }

    private void run(String owner, String sessionId, String operationId, AiClient ai) {
        if (!interviews.startReportScoring(owner, sessionId, operationId)) return;
        try {
            interviews.report(owner, sessionId, () -> ai, true);
            interviews.completeReportScoring(owner, sessionId, operationId);
            enqueueReview(owner, sessionId, "review-get-" + sessionId, () -> ai, false);
        } catch (Throwable cause) {
            try { interviews.failReportScoring(owner, sessionId, operationId, cause); }
            catch (RuntimeException ignored) { /* Expired or deleted sessions must stay absent. */ }
        }
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }

    public void cancelSession(String sessionId) { tasks.cancel(sessionId); }
}
