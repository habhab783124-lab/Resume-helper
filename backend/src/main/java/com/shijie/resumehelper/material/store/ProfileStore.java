package com.shijie.resumehelper.material.store;

import com.shijie.resumehelper.common.profile.UserProfileSnapshot;

import java.util.Optional;

/**
 * 用户已确认的求职画像。
 *
 * <p>为什么单独一个存储而不是塞进 {@link MaterialStore}：
 * 它们的生命周期不同——材料是"过程"，画像用户确认一次就长期使用；
 * 而且画像会被岗位模块、简历模块、面试模块读取，是跨模块的数据。
 */
public interface ProfileStore {

    /** 保存用户确认后的画像。 */
    void save(String userId, UserProfileSnapshot profile);

    /** 读取画像。没有确认过时返回空。 */
    Optional<UserProfileSnapshot> find(String userId);

    /** 删除画像。 */
    boolean delete(String userId);
}
