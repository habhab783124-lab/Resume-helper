package com.shijie.resumehelper.interview;

import java.time.Instant;
import java.util.Optional;
import java.util.List;

/**
 * 面试快照存取。所有查询先按 owner 隔离；文件只承诺单后端进程的重启恢复，
 * 不做多实例共享写入。
 */
public interface InterviewStore {

    record HistoryItem(String id, String role, String mode, String status, Instant createdAt,
                       Instant expiresAt, String progress, String reportStatus) {}
    record HistoryPage(List<HistoryItem> items, String nextCursor) {
        public HistoryPage { items = List.copyOf(items); }
    }
    record SessionCounts(long active, long total) {}

    /** 原子写入一份快照；失败时旧文件保持可读，调用方收到 STORAGE 受控错误。 */
    void save(InterviewSnapshot snapshot);

    /** 按 owner + sessionId 读取；不存在、越权或 owner 不匹配返回空。 */
    Optional<InterviewSnapshot> find(String owner, String sessionId);

    /** 按创建请求的幂等 ID 查回会话，用于 start 重放去重；重启后仍有效。 */
    Optional<InterviewSnapshot> findByStartRequestId(String owner, String requestId);

    /** Publish a durable deletion marker before removing a session snapshot. */
    default void delete(String owner, String sessionId) { }

    default boolean isDeleted(String owner, String sessionId) { return false; }

    default HistoryPage list(String owner, int limit, String cursor, Instant now) {
        return new HistoryPage(List.of(), null);
    }
    default SessionCounts counts(String owner, Instant now) { return new SessionCounts(0, 0); }

    /** Durable owner-scoped plan preview storage. Implementations without plan storage may keep defaults. */
    default void savePlan(String owner, InterviewPlanService.StoredPlan plan) {
        throw new UnsupportedOperationException("Plan storage is not configured");
    }

    default Optional<InterviewPlanService.StoredPlan> findPlan(String owner, String planId) {
        return Optional.empty();
    }

    default Optional<InterviewPlanService.StoredPlan> findPlanByRequestId(String owner, String requestId) {
        return Optional.empty();
    }

    /** Completed and active snapshots for one owner and source, used to avoid repeating practice items. */
    default List<InterviewSnapshot> findBySource(String owner, InterviewPlan.ResumeSource source) {
        return List.of();
    }

    /** 删除 expiresAt 早于 now 的快照，返回删除份数；损坏文件跳过，由受控读取报错。 */
    int deleteExpired(Instant now);

    /** Delete at most limit expired snapshots per maintenance tick. */
    default int deleteExpired(Instant now, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return deleteExpired(now);
    }
}
