package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Objects;
import java.time.Duration;

/** Runs one claimed generation per session on the bounded application executor. */
public final class InterviewGenerationRunner implements AutoCloseable {
    private final InterviewService service;
    private final InterviewGenerationCoordinator coordinator;
    private final ExecutorService executor;
    private final ConcurrentMap<String, Future<?>> futures = new ConcurrentHashMap<>();

    public InterviewGenerationRunner(InterviewService service, InterviewGenerationCoordinator coordinator,
                                    ExecutorService executor) {
        this.service = Objects.requireNonNull(service);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.executor = Objects.requireNonNull(executor);
    }

    public void start(InterviewGenerationCoordinator.GenerationClaim claim, AiClient ai) {
        Objects.requireNonNull(claim);
        if (ai == null || !ai.available() || ai.isFallback()) {
            fail(claim, new InterviewFailure(InterviewFailure.Code.CONFIG, false,
                    "请配置模型后重试", null));
            return;
        }
        var current = service.get(claim.owner(), claim.sessionId()).generation();
        if (current == null || !claim.generationId().equals(current.generationId())
                || current.state() != InterviewSnapshot.GenerationState.QUEUED) return;

        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                InterviewService.GenerationResult result = service.generate(claim, ai);
                coordinator.commitSuccess(claim, result);
            } catch (Throwable error) {
                InterviewFailure failure = error instanceof InterviewException ie && ie.failure() != null
                        ? ie.failure()
                        : new InterviewFailure(InterviewFailure.Code.UNKNOWN, false,
                                "题目生成失败，请重试或结束面试", null);
                fail(claim, failure);
            } finally {
                futures.remove(claim.generationId());
            }
            return null;
        });
        if (futures.putIfAbsent(claim.generationId(), task) != null) return;
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejected) {
            futures.remove(claim.generationId(), task);
            fail(claim, new InterviewFailure(InterviewFailure.Code.UPSTREAM_UNAVAILABLE, true,
                    "当前面试任务较多，请稍后重试", null));
        }
    }

    public void cancel(String generationId) {
        Future<?> future = futures.remove(generationId);
        if (future != null) future.cancel(true);
    }

    /** Compatibility adapter wait; never holds the session monitor while waiting. */
    public void await(String generationId, Duration timeout) {
        Future<?> future = futures.get(generationId);
        if (future == null) return; // already reached a terminal state, or queue rejection was recorded
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new InterviewException(504, "面试仍在处理中，请读取会话状态后继续");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterviewException(503, "等待面试生成时被中断");
        } catch (ExecutionException e) {
            throw new InterviewException(500, "面试生成失败，请读取会话状态");
        }
    }

    private void fail(InterviewGenerationCoordinator.GenerationClaim claim, InterviewFailure failure) {
        try { coordinator.commitFailure(claim, failure); }
        catch (InterviewException ignored) { /* ended or superseded; late results are discarded */ }
    }

    @Override public void close() { executor.shutdownNow(); }
}
