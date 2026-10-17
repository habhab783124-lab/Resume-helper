package com.shijie.resumehelper.interview;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/** Tracks queued/running background work by its owning interview session. */
final class SessionTaskRegistry {
    private final ConcurrentMap<String, Set<FutureTask<Void>>> tasks = new ConcurrentHashMap<>();

    void submit(String sessionId, Executor executor, Runnable action) {
        AtomicReference<FutureTask<Void>> holder = new AtomicReference<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            try { action.run(); }
            finally { unregister(sessionId, holder.get()); }
            return null;
        });
        holder.set(task);
        tasks.computeIfAbsent(sessionId, ignored -> ConcurrentHashMap.newKeySet()).add(task);
        try { executor.execute(task); }
        catch (RuntimeException rejected) { unregister(sessionId, task); throw rejected; }
    }

    void cancel(String sessionId) {
        Set<FutureTask<Void>> owned = tasks.remove(sessionId);
        if (owned != null) owned.forEach(task -> task.cancel(true));
    }

    private void unregister(String sessionId, FutureTask<Void> task) {
        if (task == null) return;
        Set<FutureTask<Void>> owned = tasks.get(sessionId);
        if (owned == null) return;
        owned.remove(task);
        if (owned.isEmpty()) tasks.remove(sessionId, owned);
    }
}
