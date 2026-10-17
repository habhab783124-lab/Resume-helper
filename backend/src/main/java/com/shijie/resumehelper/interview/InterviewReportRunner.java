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
    private final InterviewService interviews;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "interview-report-scoring");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public InterviewReportRunner(InterviewService interviews) { this.interviews = interviews; }

    public InterviewAgent.InterviewReport enqueue(String owner, String sessionId, String requestId,
            Supplier<AiClient> client, boolean retry) {
        var task = interviews.queueReport(owner, sessionId, requestId, retry);
        if (task.enqueue()) {
            try {
                AiClient ai = client.get();
                executor.execute(() -> run(owner, sessionId, task.operationId(), ai));
            } catch (RuntimeException rejected) {
                interviews.failReportScoring(owner, sessionId, task.operationId(),
                        new IllegalStateException("评分队列已满，请稍后重试。", rejected));
            }
        }
        return interviews.reportSnapshot(owner, sessionId);
    }

    private void run(String owner, String sessionId, String operationId, AiClient ai) {
        if (!interviews.startReportScoring(owner, sessionId, operationId)) return;
        try {
            interviews.report(owner, sessionId, () -> ai, true);
            interviews.completeReportScoring(owner, sessionId, operationId);
        } catch (Throwable cause) {
            try { interviews.failReportScoring(owner, sessionId, operationId, cause); }
            catch (RuntimeException ignored) { /* Expired or deleted sessions must stay absent. */ }
        }
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
