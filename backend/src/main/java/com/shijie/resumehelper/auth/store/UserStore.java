package com.shijie.resumehelper.auth.store;

import com.shijie.resumehelper.auth.domain.UserAccount;

import java.util.Optional;

/**
 * 用户账号存储。课程项目先用本机文件，后续换数据库时只需要换实现。
 */
public interface UserStore {

    Optional<UserAccount> findByEmail(String email);

    Optional<UserAccount> findById(String userId);

    /**
     * 原子地创建账号。
     *
     * @return true 表示创建成功；false 表示这个邮箱已经存在
     */
    boolean create(UserAccount account);
}
