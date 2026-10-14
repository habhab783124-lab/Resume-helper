package com.shijie.resumehelper.interview;

/**
 * 语音服务抽象：音频换文字、文字换音频。
 *
 * <p>只负责厂商调用与协议解析，不处理面试状态——会话归属、当前题校验、
 * 轮次推进全部由 {@link InterviewService} 与 Task 4 的编排层负责。
 * 实现方遇到任何厂商异常时抛 {@link InterviewException}，
 * 消息面向用户可读，不包含上游原始报文或密钥。
 */
public interface SpeechClient {

    /**
     * @param text       识别出的文字，已去除首尾空白且非空
     * @param durationMs 厂商回报的音频时长，未知时为 0
     */
    record Transcript(String text, long durationMs) {}

    /**
     * @param bytes       完整音频，不含任何分片
     * @param contentType MIME 类型
     */
    record SpeechAudio(byte[] bytes, String contentType) {}

    /** 把已规范化的 16 kHz 单声道 PCM16 WAV 识别成文字。 */
    Transcript transcribe(byte[] wav, String requestId);

    /** 把题目文字合成为音频。 */
    SpeechAudio synthesize(String text, String requestId);
}
