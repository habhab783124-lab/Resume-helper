package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** WebSocket command validation, provider operation lifetime, and ACK backpressure. */
@Service
public final class InterviewVoiceStreamService {
    private static final int MAX_REQUEST_IDS = 256;
    private static final long ACK_TIMEOUT_SECONDS = 5;
    private static final ScheduledExecutorService WATCHDOG = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "interview-voice-watchdog"); t.setDaemon(true); return t; });

    private static final class Connection {
        final WebSocketSession socket;
        final String connectionId = UUID.randomUUID().toString();
        final Set<String> requestIds = new HashSet<>();
        final Map<String, Map<String, Object>> terminalMessages = new LinkedHashMap<>();
        volatile InterviewVoiceSessionRegistry.Binding binding;
        volatile long lastMessageAt = System.nanoTime();
        volatile ScheduledFuture<?> helloTimer;
        volatile ScheduledFuture<?> heartbeatTimer;
        volatile Operation active;
        long nextStreamId = 1;
        Connection(WebSocketSession socket) { this.socket = socket; }
    }

    private static final class Operation {
        final String requestId, questionId;
        final long streamId;
        final SpeechCancellation cancellation = new SpeechCancellation();
        final SpeechRequestLimiter.Lease lease;
        final AtomicBoolean terminal = new AtomicBoolean();
        final Map<Long, Long> sentSamplesBySequence = new HashMap<>();
        final Object ackMonitor = new Object();
        volatile FutureTask<Void> task;
        long nextSequence, sentSamples, playedSamples;
        long lastReceivedSequence = -1;
        Operation(String requestId, String questionId, long streamId, SpeechRequestLimiter.Lease lease) {
            this.requestId=requestId; this.questionId=questionId; this.streamId=streamId; this.lease=lease;
        }
    }

    private final InterviewVoiceSessionRegistry registry;
    private final InterviewService interviews;
    private final SpeechRequestLimiter limiter;
    private final InterviewVoiceProperties properties;
    private final ObjectMapper mapper;
    private final ThreadPoolExecutor executor;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public InterviewVoiceStreamService(InterviewVoiceSessionRegistry registry, InterviewService interviews,
            SpeechRequestLimiter limiter, InterviewVoiceProperties properties, ObjectMapper mapper,
            @Qualifier("interviewVoiceExecutor") ThreadPoolExecutor executor) {
        this.registry=registry; this.interviews=interviews; this.limiter=limiter;
        this.properties=properties; this.mapper=mapper; this.executor=executor;
    }

    public void open(WebSocketSession socket) {
        Connection connection = new Connection(socket);
        connections.put(socket.getId(), connection);
        connection.helloTimer = WATCHDOG.schedule(() -> {
            if (connection.binding == null) reject(connection, 4401, "hello timeout", "CONFIG", "语音连接握手超时");
        }, 5, TimeUnit.SECONDS);
        connection.heartbeatTimer = WATCHDOG.scheduleAtFixedRate(() -> {
            if (connections.get(socket.getId()) != connection) return;
            if (System.nanoTime() - connection.lastMessageAt > TimeUnit.SECONDS.toNanos(30)) {
                reject(connection, 4408, "session idle", "TIMEOUT", "语音连接已暂停，请重新连接");
            } else if (connection.binding != null && Instant.now().isAfter(connection.binding.expiresAt())) {
                reject(connection, 4408, "session expired", "TIMEOUT", "语音连接已过期，请重新连接");
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    public void onText(String socketId, String raw) {
        Connection c = connections.get(socketId);
        if (c == null) return;
        c.lastMessageAt = System.nanoTime();
        if (raw == null || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192) {
            protocolFailure(c, "消息超过大小限制"); return;
        }
        try {
            JsonNode message = mapper.readTree(raw);
            if (message == null || message.path("v").asInt(-1) != 1 || !message.path("type").isTextual()) {
                protocolFailure(c, "消息格式不正确"); return;
            }
            String type = message.path("type").asText();
            if (c.binding == null) {
                if (!"session.hello".equals(type) || !c.requestIds.isEmpty()
                        || !onlyFields(message, "v", "type", "voiceSessionId", "ticket")) {
                    reject(c, 4401, "hello required", "CONFIG", "语音连接凭据无效"); return;
                }
                hello(c, message); return;
            }
            switch (type) {
                case "session.ping" -> {
                    if (!onlyFields(message, "v", "type", "nonce")) { protocolFailure(c, "心跳消息字段无效"); return; }
                    send(c, Map.of("v", 1, "type", "session.pong", "nonce", message.path("nonce").asText("")));
                }
                case "session.close" -> {
                    if (!onlyFields(message, "v", "type")) { protocolFailure(c, "关闭消息字段无效"); return; }
                    clientClose(c);
                }
                case "speech.start" -> {
                    if (!onlyFields(message, "v", "type", "requestId", "questionId")) { protocolFailure(c, "语音请求字段无效"); return; }
                    start(c, message);
                }
                case "speech.cancel" -> {
                    if (!onlyFields(message, "v", "type", "requestId", "streamId")) { protocolFailure(c, "取消消息字段无效"); return; }
                    cancel(c, message);
                }
                case "audio.ack" -> {
                    if (!onlyFields(message, "v", "type", "streamId", "receivedSeq", "playedSamples")) { protocolFailure(c, "播放确认字段无效"); return; }
                    ack(c, message);
                }
                default -> protocolFailure(c, "不支持的语音指令");
            }
        } catch (IOException | RuntimeException e) {
            protocolFailure(c, "消息无法解析");
        }
    }

    public void onBinary(String socketId) {
        Connection c = connections.get(socketId);
        if (c != null) protocolFailure(c, "客户端不能发送二进制音频");
    }

    private void hello(Connection c, JsonNode message) {
        try {
            String voiceSessionId = text(message, "voiceSessionId");
            String ticket = text(message, "ticket");
            var binding = registry.consume(voiceSessionId, ticket);
            c.binding = binding;
            if (c.helloTimer != null) c.helloTimer.cancel(false);
            send(c, Map.of("v", 1, "type", "session.ready", "voiceSessionId", binding.voiceSessionId(),
                    "connectionId", c.connectionId, "protocolVersion", 1, "expiresAt", binding.expiresAt().toString(),
                    "sampleRate", 24000, "channels", 1, "maxBufferedSamples", InterviewVoiceProtocol.MAX_BUFFERED_SAMPLES,
                    "maxPcmBytes", InterviewVoiceProtocol.MAX_PCM_BYTES));
        } catch (RuntimeException e) {
            reject(c, 4401, "invalid ticket", "AUTH", "语音连接凭据无效或已使用");
        }
    }

    private void start(Connection c, JsonNode message) {
        String requestId = textOrNull(message, "requestId"), questionId = textOrNull(message, "questionId");
        if (!isUuid(requestId) || questionId == null || questionId.isBlank() || message.has("text")
                || message.has("speaker") || message.has("apiKey") || message.has("providerUrl")) {
            sendFailure(c, requestId, null, "INPUT", "语音请求参数不正确", false, null); return;
        }
        synchronized (c) {
            if (c.requestIds.contains(requestId) || c.requestIds.size() >= MAX_REQUEST_IDS) {
                sendFailure(c, requestId, null, "INPUT", "语音请求编号已使用，请重新连接", false, null); return;
            }
            if (c.active != null) {
                sendFailure(c, requestId, null, "RATE_LIMITED", "当前题目语音仍在生成", true, 1000L); return;
            }
            InterviewService.Session session;
            try { session=interviews.get(c.binding.owner(), c.binding.interviewSessionId()); }
            catch (RuntimeException e) { sendFailure(c, requestId, null, "STALE_CONTEXT", "面试状态已变化，请同步后重试", true, null); return; }
            if (!"ACTIVE".equals(session.status()) || session.pendingAnswer() != null || session.currentQuestion() == null
                    || !questionId.equals(session.currentQuestion().questionId())) {
                sendFailure(c, requestId, null, "STALE_CONTEXT", "面试状态已变化，请同步后重试", true, null); return;
            }
            SpeechRequestLimiter.Lease lease;
            try { lease=limiter.tryAcquire(c.binding.owner(), SpeechRequestLimiter.Lane.TTS); }
            catch (InterviewException e) { sendFailure(c, requestId, null, "RATE_LIMITED", e.getMessage(), true,
                    e.failure() == null ? 1000L : e.failure().retryAfterMs()); return; }
            if (c.nextStreamId > 0xffff_ffffL) { lease.close(); reject(c, 1002, "stream id exhausted", "INPUT", "语音连接需要重新建立"); return; }
            Operation op = new Operation(requestId, questionId, c.nextStreamId++, lease);
            c.active=op; c.requestIds.add(requestId);
            send(c, Map.of("v", 1, "type", "speech.started", "requestId", requestId, "questionId", questionId,
                    "streamId", op.streamId, "format", "pcm_s16le", "sampleRate", 24000, "channels", 1));
            FutureTask<Void> task = new FutureTask<>(() -> { runStream(c, op, session.currentQuestion().question()); return null; });
            op.task=task;
            try { executor.execute(task); }
            catch (RuntimeException rejected) {
                c.active=null; lease.close(); sendFailure(c, requestId, op.streamId, "RATE_LIMITED", "语音服务繁忙，请稍后重试", true, 1000L);
            }
        }
    }

    private void runStream(Connection c, Operation op, String question) {
        try {
            var options = new StreamingSpeechClient.StreamOptions(properties.getFirstAudioTimeoutSeconds(),
                    properties.getUpstreamIdleTimeoutSeconds(), properties.getStreamTimeoutSeconds(), properties.getMaxStreamAudioBytes());
            var result = c.binding.speechClient().streamQuestion(question, UUID.randomUUID().toString(), options,
                    op.cancellation, new StreamingSpeechClient.PcmSink() {
                        public void onFormat(StreamingSpeechClient.AudioFormat format) {
                            if (!"pcm_s16le".equals(format.encoding()) || format.sampleRate()!=24000 || format.channels()!=1)
                                throw new InterviewException(502, "供应商音频格式无效");
                        }
                        public void onAudio(byte[] pcm) { sendPcm(c, op, pcm); }
                    });
            if (op.terminal.compareAndSet(false, true)) terminal(c, op, Map.of("v", 1, "type", "speech.ended",
                    "requestId", op.requestId, "streamId", op.streamId,
                    "lastSeq", op.nextSequence - 1, "totalSamples", result.totalSamples(), "totalBytes", result.totalBytes()));
        } catch (RuntimeException e) {
            if (op.terminal.compareAndSet(false, true)) {
                String code = failureCode(e);
                Map<String,Object> failed=failureBody(op.requestId,op.streamId,code,
                        e instanceof InterviewException ? e.getMessage() : "题目语音生成失败，请重试",true,
                        e instanceof InterviewException ie && ie.failure()!=null ? ie.failure().retryAfterMs() : null);
                terminal(c,op,failed);
            }
        } finally {
            op.lease.close();
            synchronized (c) { if (c.active == op) c.active=null; }
        }
    }

    private void sendPcm(Connection c, Operation op, byte[] pcm) {
        InterviewService.Session session = interviews.get(c.binding.owner(), c.binding.interviewSessionId());
        if (!"ACTIVE".equals(session.status()) || session.pendingAnswer()!=null || session.currentQuestion()==null
                || !op.questionId.equals(session.currentQuestion().questionId()))
            throw new InterviewException(409, "面试题目已变化");
        if ((pcm.length & 1) != 0 || pcm.length == 0) throw new InterviewException(502, "音频帧格式无效");
        int offset = 0;
        while (offset < pcm.length) {
            int size = Math.min(InterviewVoiceProtocol.MAX_PCM_BYTES, pcm.length - offset) & ~1;
            awaitWindow(c, op, size / 2);
            short[] samples = new short[size / 2];
            ByteBuffer bytes = ByteBuffer.wrap(pcm, offset, size).order(ByteOrder.LITTLE_ENDIAN);
            for (int i=0;i<samples.length;i++) samples[i]=bytes.getShort();
            synchronized (op.ackMonitor) {
                long sequence=op.nextSequence++;
                op.sentSamples += samples.length;
                op.sentSamplesBySequence.put(sequence, op.sentSamples);
                sendBinary(c, InterviewVoiceProtocol.encodePcmFrame(new InterviewVoiceProtocol.PcmFrame(
                        op.streamId, sequence, op.sentSamples - samples.length, samples)));
                while (op.sentSamplesBySequence.size() > 4096) op.sentSamplesBySequence.remove(op.sentSamplesBySequence.keySet().iterator().next());
            }
            offset += size;
        }
    }

    private void awaitWindow(Connection c, Operation op, int nextSamples) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(ACK_TIMEOUT_SECONDS);
        synchronized (op.ackMonitor) {
            while (op.sentSamples + nextSamples - op.playedSamples > InterviewVoiceProtocol.MAX_BUFFERED_SAMPLES) {
                op.cancellation.throwIfCancelled();
                long remaining=deadline-System.nanoTime();
                if (remaining<=0) throw new InterviewException(504, "播放端未及时确认语音");
                try { TimeUnit.NANOSECONDS.timedWait(op.ackMonitor, remaining); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException(); }
            }
        }
    }

    private void ack(Connection c, JsonNode message) {
        Operation op=c.active;
        if (op==null || message.path("streamId").asLong(-1)!=op.streamId) return;
        long received=message.path("receivedSeq").asLong(-2), played=message.path("playedSamples").asLong(-1);
        synchronized (op.ackMonitor) {
            long receivedSamples = received < 0 ? 0 : op.sentSamplesBySequence.getOrDefault(received, -1L);
            if (received < op.lastReceivedSequence || received < -1 || received >= op.nextSequence
                    || receivedSamples < 0 || played < op.playedSamples
                    || played > receivedSamples) { protocolFailure(c, "播放进度无效"); return; }
            op.lastReceivedSequence=received;
            if (played > op.playedSamples) { op.playedSamples=played; op.ackMonitor.notifyAll(); }
        }
    }

    private void cancel(Connection c, JsonNode message) {
        String requestId=textOrNull(message, "requestId");
        Operation op=c.active;
        if (op==null || !op.requestId.equals(requestId)) {
            Map<String,Object> previous=c.terminalMessages.get(requestId);
            if(previous!=null) send(c,previous);
            else sendFailure(c,requestId,null,"INPUT","语音请求编号无效",false,null);
            return;
        }
        op.cancellation.cancel();
        synchronized (op.ackMonitor) { op.ackMonitor.notifyAll(); }
        if (op.terminal.compareAndSet(false, true)) terminal(c,op,Map.of("v", 1, "type", "speech.cancelled", "requestId", op.requestId, "streamId", op.streamId));
        FutureTask<Void> task=op.task;
        if (task != null && executor.remove(task)) {
            task.cancel(false); op.lease.close(); synchronized (c) { if (c.active==op) c.active=null; }
        }
    }

    private void sendFailure(Connection c, String requestId, Long streamId, String code, String message,
                             boolean retryable, Long retryAfter) {
        send(c,failureBody(requestId,streamId,code,message,retryable,retryAfter));
    }
    private Map<String,Object> failureBody(String requestId, Long streamId, String code, String message,
                                           boolean retryable, Long retryAfter) {
        Map<String,Object> body=new HashMap<>(); body.put("v",1); body.put("type","speech.failed");
        if (requestId!=null) body.put("requestId",requestId); if (streamId!=null) body.put("streamId",streamId);
        body.put("code",code); body.put("message",message); body.put("retryable",retryable);
        if (retryAfter!=null) body.put("retryAfterMs",retryAfter); return body;
    }
    private void terminal(Connection c, Operation op, Map<String,Object> message) {
        synchronized(c) { c.terminalMessages.put(op.requestId,message); }
        send(c,message);
    }

    private void protocolFailure(Connection c, String message) { reject(c, 1002, "protocol error", "INPUT", message); }
    private void reject(Connection c, int closeCode, String reason, String code, String message) {
        send(c, Map.of("v",1,"type","session.failed","code",code,"message",message,"retryable",false));
        try { if (c.socket.isOpen()) c.socket.close(new CloseStatus(closeCode, reason)); } catch (IOException ignored) { }
    }

    public void close(String socketId) {
        Connection c=connections.remove(socketId);
        if (c==null) return;
        if (c.helloTimer!=null) c.helloTimer.cancel(false);
        if (c.heartbeatTimer!=null) c.heartbeatTimer.cancel(false);
        Operation op=c.active;
        if (op!=null) { op.cancellation.cancel(); synchronized (op.ackMonitor) { op.ackMonitor.notifyAll(); }
            if (op.task!=null && executor.remove(op.task)) { op.task.cancel(false); op.lease.close(); }
        }
        if (c.binding!=null) registry.close(c.binding.owner(),c.binding.interviewSessionId(),c.binding.voiceSessionId());
    }

    public void closeVoiceSession(String owner, String interviewSessionId, String voiceSessionId) {
        for (Connection c : connections.values()) {
            var binding=c.binding;
            if (binding!=null && binding.voiceSessionId().equals(voiceSessionId)
                    && binding.owner().equals(owner) && binding.interviewSessionId().equals(interviewSessionId))
                clientClose(c);
        }
    }

    private void clientClose(Connection c) {
        close(c.socket.getId());
        try { if(c.socket.isOpen()) c.socket.close(CloseStatus.NORMAL); } catch(IOException ignored) { }
    }

    private void send(Connection c, Object body) {
        try { synchronized (c.socket) { if (c.socket.isOpen()) c.socket.sendMessage(new TextMessage(mapper.writeValueAsString(body))); } }
        catch (Exception e) { closeQuietly(c); }
    }
    private void sendBinary(Connection c, byte[] bytes) {
        try { synchronized (c.socket) { if(c.socket.isOpen()) c.socket.sendMessage(new org.springframework.web.socket.BinaryMessage(bytes)); } }
        catch (IOException e) { closeQuietly(c); throw new InterviewException(502,"语音连接已断开"); }
    }
    private static void closeQuietly(Connection c) { try { if(c.socket.isOpen()) c.socket.close(); } catch(IOException ignored) { } }
    private static String text(JsonNode n,String key) { String s=textOrNull(n,key); if(s==null) throw new IllegalArgumentException(); return s; }
    private static String textOrNull(JsonNode n,String key) { JsonNode v=n.get(key); return v!=null&&v.isTextual()?v.asText():null; }
    private static boolean isUuid(String s) {
        try { return s != null && UUID.fromString(s).toString().equalsIgnoreCase(s); }
        catch(IllegalArgumentException e) { return false; }
    }
    private static boolean onlyFields(JsonNode value,String... allowed) {
        Set<String> names=Set.of(allowed);
        var fields=value.fieldNames();
        while(fields.hasNext()) if(!names.contains(fields.next())) return false;
        return true;
    }
    private static String failureCode(RuntimeException error) {
        if (error instanceof InterviewException ie) {
            return switch (ie.status()) {
                case 400 -> "INPUT"; case 401, 403 -> "AUTH"; case 409 -> "CONFLICT";
                case 429 -> "RATE_LIMITED"; case 504 -> "TIMEOUT"; default -> "UPSTREAM_UNAVAILABLE";
            };
        }
        return "UPSTREAM_UNAVAILABLE";
    }
}
