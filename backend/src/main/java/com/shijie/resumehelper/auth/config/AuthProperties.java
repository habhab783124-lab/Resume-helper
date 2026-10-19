package com.shijie.resumehelper.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 账号认证配置，对应 {@code application.yml} 的 {@code app.auth.*}。
 */
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** 账号文件存放目录。默认 {@code ./data/auth/users.json}。 */
    private String storageDir = "./data/auth";

    /**
     * 令牌签名密钥。留空时启动会生成临时随机密钥，重启后已登录用户需要重新登录。
     * 正式部署请通过环境变量 {@code AUTH_TOKEN_SECRET} 配置一个足够长的随机串。
     */
    private String tokenSecret = "";

    /** 登录令牌有效期（天）。 */
    private int tokenTtlDays = 7;

    /**
     * 是否强制所有业务接口都带登录令牌。
     *
     * <p>默认开启。设为 false 时保留旧的 {@code X-User-Id} 演示请求头，
     * 只适合本机课程演示，不应部署到公网。
     */
    private boolean enforce = true;

    /** 密码最短长度。 */
    private int passwordMinLength = 8;

    /** 密码最长长度，防止超长输入拖慢哈希。 */
    private int passwordMaxLength = 128;

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public String getTokenSecret() {
        return tokenSecret;
    }

    public void setTokenSecret(String tokenSecret) {
        this.tokenSecret = tokenSecret;
    }

    public int getTokenTtlDays() {
        return tokenTtlDays;
    }

    public void setTokenTtlDays(int tokenTtlDays) {
        this.tokenTtlDays = tokenTtlDays;
    }

    public boolean isEnforce() {
        return enforce;
    }

    public void setEnforce(boolean enforce) {
        this.enforce = enforce;
    }

    public int getPasswordMinLength() {
        return passwordMinLength;
    }

    public void setPasswordMinLength(int passwordMinLength) {
        this.passwordMinLength = passwordMinLength;
    }

    public int getPasswordMaxLength() {
        return passwordMaxLength;
    }

    public void setPasswordMaxLength(int passwordMaxLength) {
        this.passwordMaxLength = passwordMaxLength;
    }
}
