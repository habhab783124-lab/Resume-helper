package com.shijie.resumehelper.resume.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 简历模块配置，对应 application.yml 的 {@code app.resume.*}。
 */
@ConfigurationProperties(prefix = "app.resume")
public class ResumeModuleProperties {

    /** 保存的简历版本存放目录。相对路径相对于 JVM 工作目录。 */
    private String storageDir = "./data/resumes";

    /** 每个用户最多保留多少版。超出后保存新版本会提示先删旧的。 */
    private int maxVersionsPerUser = 20;

    /** 目标岗位描述（JD）的长度上限。用户粘贴整页 JD 时截断，避免提示词失控。 */
    private int maxJdChars = 6000;

    /** 一次最多让模型给几条建议。条数越多越费 token，太多用户也看不完。 */
    private int maxSuggestions = 8;

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public int getMaxVersionsPerUser() {
        return maxVersionsPerUser;
    }

    public void setMaxVersionsPerUser(int maxVersionsPerUser) {
        this.maxVersionsPerUser = maxVersionsPerUser;
    }

    public int getMaxJdChars() {
        return maxJdChars;
    }

    public void setMaxJdChars(int maxJdChars) {
        this.maxJdChars = maxJdChars;
    }

    public int getMaxSuggestions() {
        return maxSuggestions;
    }

    public void setMaxSuggestions(int maxSuggestions) {
        this.maxSuggestions = maxSuggestions;
    }
}
