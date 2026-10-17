package com.shijie.resumehelper.interview;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.interview.voice")
public class InterviewVoiceProperties {
    private boolean enabled;
    private int ticketTtlSeconds = 60;
    private int connectionTtlSeconds = 1800;
    private int maxSessions = 32;
    private int maxSessionsPerOwner = 4;
    private int firstAudioTimeoutSeconds = 15;
    private int upstreamIdleTimeoutSeconds = 15;
    private int streamTimeoutSeconds = 180;
    private long maxStreamAudioBytes = 16_777_216L;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getTicketTtlSeconds() { return ticketTtlSeconds; }
    public void setTicketTtlSeconds(int value) { this.ticketTtlSeconds = value; }
    public int getConnectionTtlSeconds() { return connectionTtlSeconds; }
    public void setConnectionTtlSeconds(int value) { this.connectionTtlSeconds = value; }
    public int getMaxSessions() { return maxSessions; }
    public void setMaxSessions(int value) { this.maxSessions = value; }
    public int getMaxSessionsPerOwner() { return maxSessionsPerOwner; }
    public void setMaxSessionsPerOwner(int value) { this.maxSessionsPerOwner = value; }
    public int getFirstAudioTimeoutSeconds() { return firstAudioTimeoutSeconds; }
    public void setFirstAudioTimeoutSeconds(int value) { this.firstAudioTimeoutSeconds = value; }
    public int getUpstreamIdleTimeoutSeconds() { return upstreamIdleTimeoutSeconds; }
    public void setUpstreamIdleTimeoutSeconds(int value) { this.upstreamIdleTimeoutSeconds = value; }
    public int getStreamTimeoutSeconds() { return streamTimeoutSeconds; }
    public void setStreamTimeoutSeconds(int value) { this.streamTimeoutSeconds = value; }
    public long getMaxStreamAudioBytes() { return maxStreamAudioBytes; }
    public void setMaxStreamAudioBytes(long value) { this.maxStreamAudioBytes = value; }
}
