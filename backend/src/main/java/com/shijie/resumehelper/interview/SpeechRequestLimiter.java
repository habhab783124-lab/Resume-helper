package com.shijie.resumehelper.interview;

import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared, non-queueing process-local limit for every speech endpoint. */
@Component
public final class SpeechRequestLimiter {
    public enum Lane { ASR, TTS }

    public interface Lease extends AutoCloseable {
        @Override void close();
    }

    private final int maxConcurrent;
    private final Map<String, Integer> activeByOwner = new HashMap<>();
    private final Map<String, Set<Lane>> activeLanesByOwner = new HashMap<>();
    private int active;

    public SpeechRequestLimiter(SpeechProperties properties) {
        this.maxConcurrent = Math.max(1, properties.getMaxConcurrentRequests());
    }

    public synchronized Lease tryAcquire(String owner, Lane lane) {
        if (owner == null || owner.isBlank() || lane == null) throw new IllegalArgumentException("语音请求归属无效");
        if (active >= maxConcurrent || activeLanesByOwner.getOrDefault(owner, Set.of()).contains(lane)) {
            throw new InterviewException(429, "语音服务繁忙，请稍后再试",
                    new InterviewFailure(InterviewFailure.Code.RATE_LIMITED, true, "语音服务繁忙，请稍后再试", 1000L));
        }
        active++;
        activeByOwner.merge(owner, 1, Integer::sum);
        activeLanesByOwner.computeIfAbsent(owner, ignored -> EnumSet.noneOf(Lane.class)).add(lane);
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (!released.compareAndSet(false, true)) return;
            synchronized (SpeechRequestLimiter.this) {
                active--;
                activeLanesByOwner.getOrDefault(owner, Set.of()).remove(lane);
                int remaining = activeByOwner.getOrDefault(owner, 0) - 1;
                if (remaining <= 0) {
                    activeByOwner.remove(owner);
                    activeLanesByOwner.remove(owner);
                } else {
                    activeByOwner.put(owner, remaining);
                }
            }
        };
    }

    synchronized int activeCount() { return active; }
}
