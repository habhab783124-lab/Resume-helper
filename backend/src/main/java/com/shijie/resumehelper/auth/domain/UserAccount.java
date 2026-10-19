package com.shijie.resumehelper.auth.domain;

/**
 * 一个真实注册的用户账号。
 *
 * <p>{@code passwordHash} 只保存 PBKDF2 哈希，绝不保存明文密码。
 * {@code email} 统一小写后保存，保证同一个邮箱不会注册出两个账号。
 */
public record UserAccount(
        String id,
        String email,
        String displayName,
        String passwordHash,
        String createdAt
) {
}
