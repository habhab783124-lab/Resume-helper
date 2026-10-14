package com.shijie.resumehelper.resume.store;

import com.shijie.resumehelper.resume.domain.ResumeVersion;

import java.util.List;
import java.util.Optional;

/**
 * 简历版本的持久化。
 *
 * <p>和 {@code JobStore} / {@code MaterialStore} 一样是接口，当前实现是文件系统
 * （零外部依赖，克隆下来就能跑）。换 MySQL 只需加一个实现类。
 */
public interface ResumeVersionStore {

    /** 保存一版（新增或覆盖同 id）。 */
    ResumeVersion save(ResumeVersion version);

    /** 按 id 取。必须带 userId，避免越权读到别人的简历。 */
    Optional<ResumeVersion> find(String userId, String versionId);

    /** 列出该用户的全部版本，最近保存的在前。 */
    List<ResumeVersion> list(String userId);

    /** 当前已保存的版本数，用于判断是否超过上限。 */
    int count(String userId);

    boolean delete(String userId, String versionId);
}
