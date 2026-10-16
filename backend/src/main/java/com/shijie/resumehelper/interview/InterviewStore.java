package com.shijie.resumehelper.interview;

import java.time.Instant;
import java.util.Optional;

/**
 * 面试快照存取。所有查询先按 owner 隔离；文件只承诺单后端进程的重启恢复，
 * 不做多实例共享写入。
 */
public interface InterviewStore {

    /** 原子写入一份快照；失败时旧文件保持可读，调用方收到 STORAGE 受控错误。 */
    void save(InterviewSnapshot snapshot);

    /** 按 owner + sessionId 读取；不存在、越权或 owner 不匹配返回空。 */
    Optional<InterviewSnapshot> find(String owner, String sessionId);

    /** 按创建请求的幂等 ID 查回会话，用于 start 重放去重；重启后仍有效。 */
    Optional<InterviewSnapshot> findByStartRequestId(String owner, String requestId);

    /** 删除 expiresAt 早于 now 的快照，返回删除份数；损坏文件跳过，由受控读取报错。 */
    int deleteExpired(Instant now);
}
