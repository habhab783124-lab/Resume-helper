package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class InterviewVoiceProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void pcmCodecMatchesSharedCrossLanguageVector() throws Exception {
        JsonNode vector = JSON.readTree(Files.readString(Path.of("../docs/contracts/interview-voice-v1.json")))
                .path("pcmFrame").path("fixedVector");
        short[] samples = new short[vector.path("samples").size()];
        for (int i = 0; i < samples.length; i++) samples[i] = (short) vector.path("samples").get(i).intValue();
        byte[] expected = HexFormat.of().parseHex(vector.path("frameHex").asText());
        byte[] encoded = InterviewVoiceProtocol.encodePcmFrame(new InterviewVoiceProtocol.PcmFrame(
                vector.path("streamId").intValue(), vector.path("sequence").intValue(),
                vector.path("sampleOffset").intValue(), samples));
        assertArrayEquals(expected, encoded);
        var decoded = InterviewVoiceProtocol.decodePcmFrame(ByteBuffer.wrap(expected).order(ByteOrder.BIG_ENDIAN));
        assertEquals(vector.path("streamId").intValue(), decoded.streamId());
        assertEquals(vector.path("sequence").intValue(), decoded.sequence());
        assertEquals(vector.path("sampleOffset").intValue(), decoded.sampleOffset());
        assertArrayEquals(samples, decoded.samples());
    }

    @Test
    void decoderRejectsInvalidHeadersLengthsAndLimits() {
        assertThrows(IllegalArgumentException.class, () -> InterviewVoiceProtocol.decodePcmFrame(ByteBuffer.allocate(20)));
        byte[] valid = InterviewVoiceProtocol.encodePcmFrame(new InterviewVoiceProtocol.PcmFrame(1, 0, 0, new short[]{1}));
        byte[] badMagic = valid.clone(); badMagic[0] = 0;
        assertThrows(IllegalArgumentException.class, () -> InterviewVoiceProtocol.decodePcmFrame(ByteBuffer.wrap(badMagic)));
        byte[] badVersion = valid.clone(); badVersion[4] = 2;
        assertThrows(IllegalArgumentException.class, () -> InterviewVoiceProtocol.decodePcmFrame(ByteBuffer.wrap(badVersion)));
        byte[] oddPayload = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IllegalArgumentException.class, () -> InterviewVoiceProtocol.decodePcmFrame(ByteBuffer.wrap(oddPayload)));
        byte[] wrongCount = valid.clone(); wrongCount[20] = 2;
        assertThrows(IllegalArgumentException.class, () -> InterviewVoiceProtocol.decodePcmFrame(ByteBuffer.wrap(wrongCount)));
        byte[] wrongReserved = valid.clone(); wrongReserved[28] = 1;
        assertThrows(IllegalArgumentException.class, () -> InterviewVoiceProtocol.decodePcmFrame(ByteBuffer.wrap(wrongReserved)));
        assertThrows(IllegalArgumentException.class, () -> InterviewVoiceProtocol.encodePcmFrame(
                new InterviewVoiceProtocol.PcmFrame(1, 0, 0, new short[2049])));
    }
}
