package com.shijie.resumehelper.job.store;

import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.common.util.TextKit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内的岗位存储实现。
 *
 * <p>定位是「联调期可用」：不需要 MySQL 就能把详情、收藏跑通，
 * 让岗位模块不被数据库进度阻塞。重启后数据丢失，这是刻意接受的代价。
 *
 * <p>容量做了上限，避免长期运行后内存无界增长。
 */
public final class InMemoryJobStore implements JobStore {

    /** 最多缓存多少个岗位。 */
    private static final int MAX_JOBS = 2000;

    private final Map<String, Job> jobs = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Job> eldest) {
                    return size() > MAX_JOBS;
                }
            });

    /** userId -> 收藏的 jobId（LinkedHashSet 保留收藏顺序）。 */
    private final Map<String, Set<String>> favorites = new ConcurrentHashMap<>();

    @Override
    public void put(Job job) {
        if (job != null) {
            jobs.put(job.id(), job);
        }
    }

    @Override
    public void putAll(Collection<Job> batch) {
        if (batch != null) {
            for (Job job : batch) {
                put(job);
            }
        }
    }

    @Override
    public Optional<Job> find(String id) {
        return TextKit.isBlank(id) ? Optional.empty() : Optional.ofNullable(jobs.get(id));
    }

    @Override
    public List<Job> recent(int limit) {
        List<Job> snapshot;
        synchronized (jobs) {
            snapshot = new ArrayList<>(jobs.values());
        }
        Collections.reverse(snapshot);
        return limit > 0 && snapshot.size() > limit ? new ArrayList<>(snapshot.subList(0, limit)) : snapshot;
    }

    @Override
    public boolean save(String userId, String jobId) {
        if (TextKit.isBlank(userId) || TextKit.isBlank(jobId)) {
            return false;
        }
        return favorites.computeIfAbsent(userId, key -> Collections.synchronizedSet(new LinkedHashSet<>())).add(jobId);
    }

    @Override
    public boolean unsave(String userId, String jobId) {
        Set<String> saved = favorites.get(userId);
        return saved != null && saved.remove(jobId);
    }

    @Override
    public boolean isSaved(String userId, String jobId) {
        Set<String> saved = favorites.get(userId);
        return saved != null && saved.contains(jobId);
    }

    @Override
    public List<Job> saved(String userId) {
        Set<String> ids = favorites.get(userId);
        if (ids == null) {
            return List.of();
        }
        List<String> ordered;
        synchronized (ids) {
            ordered = new ArrayList<>(ids);
        }
        Collections.reverse(ordered);
        List<Job> result = new ArrayList<>();
        for (String id : ordered) {
            Job job = jobs.get(id);
            if (job != null) {
                result.add(job);
            }
        }
        return result;
    }
}
