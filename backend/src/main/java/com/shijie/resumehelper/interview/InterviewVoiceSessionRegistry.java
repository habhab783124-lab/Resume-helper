package com.shijie.resumehelper.interview;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** In-memory one-time ticket registry; only a SHA-256 ticket digest is indexed. */
@Component
public final class InterviewVoiceSessionRegistry {
    public record Ticket(String voiceSessionId, String ticket, Instant ticketExpiresAt,
                         String websocketPath, int protocolVersion) {}
    public record Binding(String voiceSessionId, String owner, String interviewSessionId,
                          StreamingSpeechClient speechClient, Instant expiresAt) {}
    private static final class Entry {
        final String id, hash, owner, interviewSessionId;
        final StreamingSpeechClient client;
        final Instant ticketExpiresAt;
        Instant connectionExpiresAt;
        boolean consumed;
        Entry(String id, String hash, String owner, String interviewSessionId,
              StreamingSpeechClient client, Instant ticketExpiresAt) {
            this.id=id; this.hash=hash; this.owner=owner; this.interviewSessionId=interviewSessionId;
            this.client=client; this.ticketExpiresAt=ticketExpiresAt;
        }
    }

    private final InterviewVoiceProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> entries = new HashMap<>();

    @Autowired
    public InterviewVoiceSessionRegistry(InterviewVoiceProperties properties) { this(properties, Clock.systemUTC()); }
    InterviewVoiceSessionRegistry(InterviewVoiceProperties properties, Clock clock) {
        this.properties = properties; this.clock = clock;
    }

    public synchronized Ticket issue(String owner, String sessionId, StreamingSpeechClient client) {
        sweep();
        if (!properties.isEnabled()) throw new InterviewException(503, "连续语音暂未启用");
        long ownerCount = entries.values().stream().filter(e -> e.owner.equals(owner)).count();
        if (entries.size() >= properties.getMaxSessions() || ownerCount >= properties.getMaxSessionsPerOwner())
            throw new InterviewException(429, "连续语音连接数已满，请稍后重试",
                    new InterviewFailure(InterviewFailure.Code.RATE_LIMITED, true, "连续语音连接数已满，请稍后重试", 1000L));
        byte[] secret = new byte[32]; random.nextBytes(secret);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String id = java.util.UUID.randomUUID().toString();
        Instant expires = clock.instant().plusSeconds(Math.max(1, properties.getTicketTtlSeconds()));
        entries.put(id, new Entry(id, hash(ticket), owner, sessionId, client, expires));
        return new Ticket(id, ticket, expires, "/api/interview-voice/ws", 1);
    }

    public synchronized Binding consume(String voiceSessionId, String ticket) {
        sweep();
        Entry entry = entries.get(voiceSessionId);
        if (entry == null || entry.consumed || ticket == null || !constantTimeEquals(entry.hash, hash(ticket)))
            throw new InterviewException(401, "语音连接凭据无效或已使用");
        if (!clock.instant().isBefore(entry.ticketExpiresAt)) {
            entries.remove(voiceSessionId);
            throw new InterviewException(401, "语音连接凭据已过期");
        }
        entry.consumed = true;
        entry.connectionExpiresAt = clock.instant().plusSeconds(Math.max(1, properties.getConnectionTtlSeconds()));
        return binding(entry);
    }

    public synchronized boolean close(String owner, String sessionId, String voiceSessionId) {
        sweep();
        Entry entry = entries.get(voiceSessionId);
        if (entry == null) return false;
        if (!entry.owner.equals(owner) || !entry.interviewSessionId.equals(sessionId))
            throw new InterviewException(404, "语音连接不存在");
        entries.remove(voiceSessionId);
        return true;
    }

    @Scheduled(fixedDelay = 5000)
    public synchronized void sweep() {
        Instant now = clock.instant();
        entries.values().removeIf(e -> (!e.consumed && !now.isBefore(e.ticketExpiresAt))
                || (e.consumed && !now.isBefore(e.connectionExpiresAt)));
    }

    synchronized int size() { return entries.size(); }
    private static Binding binding(Entry e) { return new Binding(e.id, e.owner, e.interviewSessionId, e.client, e.connectionExpiresAt); }
    private static String hash(String ticket) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.US_ASCII), b.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
