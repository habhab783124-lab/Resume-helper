package com.shijie.resumehelper.interview;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Shared version-1 WebSocket PCM frame contract. */
public final class InterviewVoiceProtocol {
    public static final int VERSION = 1;
    public static final int SAMPLE_RATE = 24_000;
    public static final int CHANNELS = 1;
    public static final int HEADER_BYTES = 32;
    public static final int MAX_PCM_BYTES = 4_096;
    public static final int MAX_BUFFERED_SAMPLES = 48_000;
    private static final byte[] MAGIC = {'R', 'V', 'V', '1'};

    private InterviewVoiceProtocol() {}

    public record PcmFrame(long streamId, long sequence, long sampleOffset, short[] samples) {
        public PcmFrame {
            if (streamId < 1 || streamId > 0xffff_ffffL || sequence < 0 || sequence > 0xffff_ffffL
                    || sampleOffset < 0 || sampleOffset > 0xffff_ffffL || samples == null
                    || samples.length == 0 || samples.length * 2 > MAX_PCM_BYTES)
                throw new IllegalArgumentException("PCM 帧字段无效");
            samples = samples.clone();
        }
        @Override public short[] samples() { return samples.clone(); }
    }

    public static byte[] encodePcmFrame(PcmFrame frame) {
        short[] samples = frame.samples();
        int payloadBytes = samples.length * 2;
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + payloadBytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(MAGIC).put((byte) VERSION).put((byte) 1).putShort((short) 0);
        buffer.putInt((int) frame.streamId()).putInt((int) frame.sequence()).putInt((int) frame.sampleOffset());
        buffer.putInt(samples.length).putInt(payloadBytes).putInt(0);
        for (short sample : samples) buffer.putShort(sample);
        return buffer.array();
    }

    public static PcmFrame decodePcmFrame(ByteBuffer input) {
        if (input == null || input.remaining() < HEADER_BYTES + 2)
            throw new IllegalArgumentException("PCM 帧长度无效");
        ByteBuffer buffer = input.slice().order(ByteOrder.LITTLE_ENDIAN);
        for (byte expected : MAGIC) if (buffer.get() != expected) throw new IllegalArgumentException("PCM 帧头无效");
        int version = Byte.toUnsignedInt(buffer.get());
        int kind = Byte.toUnsignedInt(buffer.get());
        int flags = Short.toUnsignedInt(buffer.getShort());
        long streamId = Integer.toUnsignedLong(buffer.getInt());
        long sequence = Integer.toUnsignedLong(buffer.getInt());
        long sampleOffset = Integer.toUnsignedLong(buffer.getInt());
        long sampleCount = Integer.toUnsignedLong(buffer.getInt());
        long payloadBytes = Integer.toUnsignedLong(buffer.getInt());
        long reserved = Integer.toUnsignedLong(buffer.getInt());
        if (version != VERSION || kind != 1 || flags != 0 || reserved != 0 || streamId == 0
                || payloadBytes < 2 || payloadBytes > MAX_PCM_BYTES || (payloadBytes & 1) != 0
                || sampleCount != payloadBytes / 2 || input.remaining() != HEADER_BYTES + payloadBytes)
            throw new IllegalArgumentException("PCM 帧内容无效");
        short[] samples = new short[(int) sampleCount];
        for (int i = 0; i < samples.length; i++) samples[i] = buffer.getShort();
        return new PcmFrame(streamId, sequence, sampleOffset, samples);
    }
}
