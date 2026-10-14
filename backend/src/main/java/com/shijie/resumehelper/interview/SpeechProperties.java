package com.shijie.resumehelper.interview;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 面试语音配置，对应 application.yml 的 {@code app.interview.speech.*}。
 *
 * <p>故意不复用岗位模块的 {@code app.job.ai.*}：那是文本模型的 OpenAI 兼容配置，
 * 密钥是另一套体系。实测方舟 {@code ark-} 前缀的 key 调火山语音接口直接 401，
 * 两边不能互相借用。
 */
@ConfigurationProperties(prefix = "app.interview.speech")
public class SpeechProperties {

    /** 豆包语音 API key。留空表示语音能力关闭，文字面试不受影响。 */
    private String apiKey = "";

    /**
     * ASR 资源 id，按账号实际开通情况填。
     *
     * <p>计划里原定 {@code volc.bigasr.auc_turbo}，实测本账号返回
     * {@code 45000030 resource not granted}；改用 {@code volc.seedasr.sauc.duration}
     * 调同一个端点可完整识别。两个资源是不同计费方式，换号前必须实测。
     */
    private String asrResourceId = "volc.seedasr.sauc.duration";

    private String ttsResourceId = "seed-tts-2.0";

    private String ttsSpeaker = "zh_female_vv_uranus_bigtts";

    private String ffmpegPath = "ffmpeg";

    private String ffprobePath = "ffprobe";

    /** 单次上传上限 5 MiB。120 秒 16 kHz 单声道 PCM16 约 3.84 MB。 */
    private int maxAudioBytes = 5 * 1024 * 1024;

    /** 单次录音时长上限 120 秒。 */
    private int maxDurationSeconds = 120;

    /** ASR 总调用超时。 */
    private int asrTimeoutSeconds = 60;

    /** TTS 请求与音频流消费的超时。 */
    private int ttsTimeoutSeconds = 30;

    /** FFmpeg / FFprobe 子进程超时。 */
    private int conversionTimeoutSeconds = 10;

    /** 语音请求的进程内并发上限，由 Task 4 的 Semaphore 使用。 */
    private int maxConcurrentRequests = 2;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getAsrResourceId() {
        return asrResourceId;
    }

    public void setAsrResourceId(String asrResourceId) {
        this.asrResourceId = asrResourceId;
    }

    public String getTtsResourceId() {
        return ttsResourceId;
    }

    public void setTtsResourceId(String ttsResourceId) {
        this.ttsResourceId = ttsResourceId;
    }

    public String getTtsSpeaker() {
        return ttsSpeaker;
    }

    public void setTtsSpeaker(String ttsSpeaker) {
        this.ttsSpeaker = ttsSpeaker;
    }

    public String getFfmpegPath() {
        return ffmpegPath;
    }

    public void setFfmpegPath(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    public String getFfprobePath() {
        return ffprobePath;
    }

    public void setFfprobePath(String ffprobePath) {
        this.ffprobePath = ffprobePath;
    }

    public int getMaxAudioBytes() {
        return maxAudioBytes;
    }

    public void setMaxAudioBytes(int maxAudioBytes) {
        this.maxAudioBytes = maxAudioBytes;
    }

    public int getMaxDurationSeconds() {
        return maxDurationSeconds;
    }

    public void setMaxDurationSeconds(int maxDurationSeconds) {
        this.maxDurationSeconds = maxDurationSeconds;
    }

    public int getAsrTimeoutSeconds() {
        return asrTimeoutSeconds;
    }

    public void setAsrTimeoutSeconds(int asrTimeoutSeconds) {
        this.asrTimeoutSeconds = asrTimeoutSeconds;
    }

    public int getTtsTimeoutSeconds() {
        return ttsTimeoutSeconds;
    }

    public void setTtsTimeoutSeconds(int ttsTimeoutSeconds) {
        this.ttsTimeoutSeconds = ttsTimeoutSeconds;
    }

    public int getConversionTimeoutSeconds() {
        return conversionTimeoutSeconds;
    }

    public void setConversionTimeoutSeconds(int conversionTimeoutSeconds) {
        this.conversionTimeoutSeconds = conversionTimeoutSeconds;
    }

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public void setMaxConcurrentRequests(int maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
    }
}
