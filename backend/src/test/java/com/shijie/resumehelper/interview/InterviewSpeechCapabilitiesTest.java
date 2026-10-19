package com.shijie.resumehelper.interview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InterviewSpeechCapabilitiesTest {
    @Test
    void requestWithoutSpeechKeyKeepsManualAndRealtimeSpeechUnavailable() {
        var speech = new SpeechProperties();
        var voice = new InterviewVoiceProperties();
        voice.setEnabled(true);
        var service = new InterviewSpeechService(null, null, speech,
                new SpeechRequestLimiter(speech), voice, null);

        var capabilities = service.capabilities("");

        assertFalse(capabilities.asrEnabled());
        assertFalse(capabilities.ttsEnabled());
        assertFalse(capabilities.ttsStreamingEnabled());
        assertEquals(1, capabilities.voiceProtocolVersion());
    }
}
