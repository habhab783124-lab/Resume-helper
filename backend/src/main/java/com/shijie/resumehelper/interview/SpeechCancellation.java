package com.shijie.resumehelper.interview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/** Idempotent cancellation signal with race-safe callback registration. */
public final class SpeechCancellation {
    private final AtomicLong nextId = new AtomicLong();
    private final Map<Long, Runnable> callbacks = new LinkedHashMap<>();
    private volatile boolean cancelled;

    public boolean isCancelled() { return cancelled; }

    public void cancel() {
        ArrayList<Runnable> pending;
        synchronized (callbacks) {
            if (cancelled) return;
            cancelled = true;
            pending = new ArrayList<>(callbacks.values());
            callbacks.clear();
        }
        for (Runnable callback : pending) {
            try { callback.run(); } catch (RuntimeException ignored) { }
        }
    }

    public void throwIfCancelled() {
        if (cancelled) throw new CancellationException("语音请求已取消");
    }

    public AutoCloseable onCancel(Runnable callback) {
        if (callback == null) throw new IllegalArgumentException("取消回调不能为空");
        long id = nextId.incrementAndGet();
        synchronized (callbacks) {
            if (!cancelled) {
                callbacks.put(id, callback);
                return () -> { synchronized (callbacks) { callbacks.remove(id); } };
            }
        }
        callback.run();
        return () -> { };
    }
}
