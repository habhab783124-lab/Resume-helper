package com.shijie.resumehelper.interview;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 面试会话配置，对应 application.yml 的 {@code app.interview.*}（计划 4.3 的初始默认值）。
 *
 * <p>{@code app.interview.speech.*} 由 {@link SpeechProperties} 独立绑定，这里不重复。
 * 重试参数与线程池在本任务先落配置，由 T06/T08 的后台执行器实际消费。
 */
@ConfigurationProperties(prefix = "app.interview")
public class InterviewProperties {

    /** 快照存放目录；相对路径相对于 JVM 工作目录。 */
    private String storageDir = "./data/interviews";

    /** 新快照保留天数；expiresAt 在创建时冻结。 */
    private int retentionDays = 30;

    /** 每次生成操作最多额外自动重试次数；总计最多 1 + maxRetries 次模型请求。 */
    private int maxRetries = 5;

    /** 一次生成操作从被接受起的总预算（秒），含排队、调用与重试间隔。 */
    private int generationDeadlineSeconds = 120;

    /** 单次模型调用的超时上限（秒）。 */
    private int attemptTimeoutSeconds = 60;

    /** 各次重试前的基础等待（毫秒），实际等待叠加小幅抖动并尊重 Retry-After。 */
    private List<Long> retryDelaysMs = List.of(1000L, 2000L, 4000L, 8000L, 16000L);

    /** 后台生成工作线程数（有界）。 */
    private int generationWorkers = 4;

    /** 等待执行的最大排队任务数；拒绝排队时操作写为明确 FAILED。 */
    private int generationQueueCapacity = 32;

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getGenerationDeadlineSeconds() {
        return generationDeadlineSeconds;
    }

    public void setGenerationDeadlineSeconds(int generationDeadlineSeconds) {
        this.generationDeadlineSeconds = generationDeadlineSeconds;
    }

    public int getAttemptTimeoutSeconds() {
        return attemptTimeoutSeconds;
    }

    public void setAttemptTimeoutSeconds(int attemptTimeoutSeconds) {
        this.attemptTimeoutSeconds = attemptTimeoutSeconds;
    }

    public List<Long> getRetryDelaysMs() {
        return retryDelaysMs;
    }

    public void setRetryDelaysMs(List<Long> retryDelaysMs) {
        this.retryDelaysMs = retryDelaysMs;
    }

    public int getGenerationWorkers() {
        return generationWorkers;
    }

    public void setGenerationWorkers(int generationWorkers) {
        this.generationWorkers = generationWorkers;
    }

    public int getGenerationQueueCapacity() {
        return generationQueueCapacity;
    }

    public void setGenerationQueueCapacity(int generationQueueCapacity) {
        this.generationQueueCapacity = generationQueueCapacity;
    }
}
