package com.shijie.resumehelper.interview;

/** Streaming question synthesis while preserving the existing one-shot MP3 API. */
public interface StreamingSpeechClient extends SpeechClient {
    record AudioFormat(String encoding, int sampleRate, int channels) {}
    record StreamResult(long totalSamples, long totalBytes) {}
    record StreamOptions(int firstAudioTimeoutSeconds, int idleTimeoutSeconds,
                         int totalTimeoutSeconds, long maxAudioBytes) {
        public StreamOptions {
            if (firstAudioTimeoutSeconds < 1 || idleTimeoutSeconds < 1 || totalTimeoutSeconds < 1 || maxAudioBytes < 2)
                throw new IllegalArgumentException("语音流限制参数无效");
        }
    }
    interface PcmSink {
        void onFormat(AudioFormat format);
        void onAudio(byte[] pcm16le);
    }
    StreamResult streamQuestion(String text, String requestId, StreamOptions options,
                                SpeechCancellation cancellation, PcmSink sink);
}
