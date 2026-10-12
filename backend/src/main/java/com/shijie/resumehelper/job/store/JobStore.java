package com.shijie.resumehelper.job.store;

import com.shijie.resumehelper.job.domain.Job;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 岗位存储：负责「按 id 查岗位」和「收藏岗位」。
 *
 * <p>检索出来的岗位是易失的（每次请求重新拉取），但前端点进详情、
 * 点收藏都需要按 id 再取一次，所以需要一层存储。
 *
 * <p>当前提供 {@link InMemoryJobStore}（进程内，重启即失）。
 * 按产品方案 5，正式实现应换成 MySQL：表结构见 {@code src/main/resources/db/schema.sql}
 * 的 {@code job} 与 {@code job_favorite}，实现本接口即可无缝替换。
 */
public interface JobStore {

    /** 写入或覆盖一个岗位。 */
    void put(Job job);

    /** 批量写入。 */
    void putAll(Collection<Job> jobs);

    /** 按 id 查找。 */
    Optional<Job> find(String id);

    /** 最近见过的岗位，新的在前。 */
    List<Job> recent(int limit);

    /** 收藏。返回 true 表示本次新收藏。 */
    boolean save(String userId, String jobId);

    /** 取消收藏。返回 true 表示本次确实取消了。 */
    boolean unsave(String userId, String jobId);

    boolean isSaved(String userId, String jobId);

    /** 某个用户收藏的岗位列表（按收藏时间倒序）。 */
    List<Job> saved(String userId);
}
