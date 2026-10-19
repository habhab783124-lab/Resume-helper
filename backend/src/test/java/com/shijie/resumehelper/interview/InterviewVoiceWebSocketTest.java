package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.shijie.resumehelper.interview.InterviewTestFixtures.question;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.interview.voice.enabled=true", "app.interview.speech.max-concurrent-requests=4"})
class InterviewVoiceWebSocketTest {
    @LocalServerPort int port;
    @Autowired InterviewVoiceSessionRegistry tickets;
    @Autowired InterviewService interviews;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void realWebSocketHandshakeStreamsQuestionPcmAndEndsOnce() throws Exception {
        var session = interviews.start("voice-test-owner", "voice-test-material", "resume", "backend", 13,
                (s, prompt) -> question("请介绍项目？"));
        String questionId = session.currentQuestion().questionId();
        var ticket = tickets.issue("voice-test-owner", session.sessionId(), new FakeStreamingSpeechClient());
        var listener = new RecordingListener();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                .buildAsync(wsUri(), listener).get(4, TimeUnit.SECONDS);
        try {
            socket.sendText(mapper.writeValueAsString(mapper.createObjectNode().put("v", 1)
                    .put("type", "session.hello").put("voiceSessionId", ticket.voiceSessionId()).put("ticket", ticket.ticket())), true).join();
            JsonNode ready = listener.awaitTextType("session.ready");
            assertTrue(ready.path("connectionId").isTextual());
            assertEquals(1, ready.path("protocolVersion").asInt());
            String requestId = UUID.randomUUID().toString();
            socket.sendText(mapper.writeValueAsString(mapper.createObjectNode().put("v", 1).put("type", "speech.start")
                    .put("requestId", requestId).put("questionId", questionId)), true).join();
            assertEquals("speech.started", listener.awaitTextType("speech.started").path("type").asText());
            byte[] frame = listener.binary.poll(3, TimeUnit.SECONDS);
            assertNotNull(frame, "PCM must arrive over a real WebSocket binary message");
            var decoded = InterviewVoiceProtocol.decodePcmFrame(ByteBuffer.wrap(frame));
            assertArrayEquals(new short[]{1, -2}, decoded.samples());
            assertEquals("speech.ended", listener.awaitTextType("speech.ended").path("type").asText());
            assertEquals(1, listener.endedMessages.get());
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(2, TimeUnit.SECONDS);
            interviews.delete("voice-test-owner", session.sessionId());
        }
    }

    @Test
    void upstreamFailureEmitsOneTerminalReleasesLeaseAndAllowsTheNextRequest() throws Exception {
        var session = interviews.start("voice-failure-owner", "voice-test-material", "resume", "backend", 13,
                (s, prompt) -> question("请介绍项目？"));
        var speech = new FailOnceStreamingSpeechClient();
        var ticket = tickets.issue("voice-failure-owner", session.sessionId(), speech);
        var listener = new RecordingListener();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                .buildAsync(wsUri(), listener).get(4, TimeUnit.SECONDS);
        try {
            sendHello(socket, ticket.voiceSessionId(), ticket.ticket());
            listener.awaitTextType("session.ready");

            String failedRequestId = UUID.randomUUID().toString();
            sendStart(socket, failedRequestId, session.currentQuestion().questionId());
            listener.awaitTextType("speech.started");
            JsonNode failed = listener.awaitTextType("speech.failed");
            assertEquals(failedRequestId, failed.path("requestId").asText());
            assertEquals(1, listener.terminalMessages.get());

            String nextRequestId = UUID.randomUUID().toString();
            sendStart(socket, nextRequestId, session.currentQuestion().questionId());
            listener.awaitTextType("speech.started");
            assertNotNull(listener.binary.poll(3, TimeUnit.SECONDS));
            assertEquals(nextRequestId, listener.awaitTextType("speech.ended").path("requestId").asText());
            assertEquals(2, listener.terminalMessages.get());
            assertEquals(2, speech.upstreamCalls.get());
        } finally {
            if (!socket.isOutputClosed()) socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(2, TimeUnit.SECONDS);
            interviews.delete("voice-failure-owner", session.sessionId());
        }
    }

    @Test
    void invalidTicketIsRejectedAfterUpgradeAndConnectionCloses() throws Exception {
        var listener = new RecordingListener();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                .buildAsync(wsUri(), listener).get(4, TimeUnit.SECONDS);
        // Rejection closes the socket immediately; the JDK send stage may therefore fail
        // after the server has already received the frame and sent session.failed.
        socket.sendText("{\"v\":1,\"type\":\"session.hello\",\"voiceSessionId\":\"missing\",\"ticket\":\"wrong\"}", true)
                .exceptionally(ignored -> null).join();
        assertEquals("session.failed", listener.awaitTextType("session.failed").path("type").asText());
        assertTrue(listener.closed.get(3, TimeUnit.SECONDS));
    }

    @Test
    void websocketHandshakeRejectsAnUnlistedOrigin() {
        var request = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "https://unlisted.example")
                .buildAsync(wsUri(), new RecordingListener());
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> request.get(4, TimeUnit.SECONDS));
    }

    @Test
    void connectionWithoutHelloTimesOutAndCloses() throws Exception {
        var listener = new RecordingListener();
        HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(wsUri(), listener).get(4, TimeUnit.SECONDS);
        JsonNode failure = listener.awaitTextType("session.failed", 7);
        assertEquals("CONFIG", failure.path("code").asText());
        assertTrue(listener.closed.get(2, TimeUnit.SECONDS));
    }

    @Test
    void oneTimeTicketIsRejectedOnItsSecondWebSocketConnection() throws Exception {
        var session = interviews.start("voice-test-owner", "voice-test-material", "resume", "backend", 13,
                (s, prompt) -> question("请介绍项目？"));
        var ticket = tickets.issue("voice-test-owner", session.sessionId(), new FakeStreamingSpeechClient());
        var firstListener = new RecordingListener();
        var first = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(wsUri(), firstListener).get(4, TimeUnit.SECONDS);
        try {
            sendHello(first, ticket.voiceSessionId(), ticket.ticket());
            assertEquals("session.ready", firstListener.awaitTextType("session.ready").path("type").asText());
            var secondListener = new RecordingListener();
            var second = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(wsUri(), secondListener).get(4, TimeUnit.SECONDS);
            sendHello(second, ticket.voiceSessionId(), ticket.ticket());
            assertEquals("session.failed", secondListener.awaitTextType("session.failed").path("type").asText());
            assertTrue(secondListener.closed.get(3, TimeUnit.SECONDS));
        } finally {
            if (!first.isOutputClosed()) first.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(2, TimeUnit.SECONDS);
            interviews.delete("voice-test-owner", session.sessionId());
        }
    }

    @Test
    void staleQuestionAndClientBinaryAreRejectedOnARealSocket() throws Exception {
        var session = interviews.start("voice-test-owner", "voice-test-material", "resume", "backend", 13,
                (s, prompt) -> question("请介绍项目？"));
        var ticket = tickets.issue("voice-test-owner", session.sessionId(), new FakeStreamingSpeechClient());
        var listener = new RecordingListener();
        var socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(wsUri(), listener).get(4, TimeUnit.SECONDS);
        try {
            sendHello(socket, ticket.voiceSessionId(), ticket.ticket());
            listener.awaitTextType("session.ready");
            socket.sendText(mapper.writeValueAsString(mapper.createObjectNode().put("v", 1).put("type", "speech.start")
                    .put("requestId", UUID.randomUUID().toString()).put("questionId", "stale-question")), true).join();
            assertEquals("STALE_CONTEXT", listener.awaitTextType("speech.failed").path("code").asText());
            socket.sendBinary(ByteBuffer.wrap(new byte[]{1}), true).exceptionally(ignored -> null).join();
            assertEquals("session.failed", listener.awaitTextType("session.failed").path("type").asText());
            assertTrue(listener.closed.get(3, TimeUnit.SECONDS));
        } finally {
            interviews.delete("voice-test-owner", session.sessionId());
        }
    }

    @Test
    void cancellingWhileWaitingForPlaybackAckWakesTheUpstreamAndEmitsOneTerminal() throws Exception {
        var session = interviews.start("voice-test-owner", "voice-test-material", "resume", "backend", 13,
                (s, prompt) -> question("请介绍项目？"));
        var speech = new AckBlockedSpeechClient();
        var ticket = tickets.issue("voice-test-owner", session.sessionId(), speech);
        var listener = new RecordingListener();
        var socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(wsUri(), listener).get(4, TimeUnit.SECONDS);
        String requestId = UUID.randomUUID().toString();
        try {
            sendHello(socket, ticket.voiceSessionId(), ticket.ticket());
            listener.awaitTextType("session.ready");
            socket.sendText(mapper.writeValueAsString(mapper.createObjectNode().put("v", 1).put("type", "speech.start")
                    .put("requestId", requestId).put("questionId", session.currentQuestion().questionId())), true).join();
            listener.awaitTextType("speech.started");
            assertTrue(speech.upstreamStarted.await(3, TimeUnit.SECONDS));
            assertNotNull(listener.binary.poll(3, TimeUnit.SECONDS), "server should fill the bounded playback window");

            socket.sendText(mapper.writeValueAsString(mapper.createObjectNode().put("v", 1).put("type", "speech.cancel")
                    .put("requestId", requestId).put("streamId", 1)), true).join();
            assertEquals("speech.cancelled", listener.awaitTextType("speech.cancelled").path("type").asText());
            assertTrue(speech.upstreamFinished.await(3, TimeUnit.SECONDS), "cancel should wake the ACK wait and close the upstream operation");
            assertEquals(1, listener.terminalMessages.get(), "the request must produce exactly one terminal message");
        } finally {
            if (!socket.isOutputClosed()) socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(2, TimeUnit.SECONDS);
            interviews.delete("voice-test-owner", session.sessionId());
        }
    }

    @Test
    void disconnectingWhileWaitingForPlaybackAckClosesTheUpstreamOperation() throws Exception {
        var session = interviews.start("voice-test-owner", "voice-test-material", "resume", "backend", 13,
                (s, prompt) -> question("请介绍项目？"));
        var speech = new AckBlockedSpeechClient();
        var ticket = tickets.issue("voice-test-owner", session.sessionId(), speech);
        var listener = new RecordingListener();
        var socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(wsUri(), listener).get(4, TimeUnit.SECONDS);
        try {
            sendHello(socket, ticket.voiceSessionId(), ticket.ticket());
            listener.awaitTextType("session.ready");
            socket.sendText(mapper.writeValueAsString(mapper.createObjectNode().put("v", 1).put("type", "speech.start")
                    .put("requestId", UUID.randomUUID().toString()).put("questionId", session.currentQuestion().questionId())), true).join();
            listener.awaitTextType("speech.started");
            assertTrue(speech.upstreamStarted.await(3, TimeUnit.SECONDS));
            assertNotNull(listener.binary.poll(3, TimeUnit.SECONDS), "server should fill the bounded playback window");

            socket.sendClose(WebSocket.NORMAL_CLOSURE, "disconnect during playback").get(2, TimeUnit.SECONDS);
            assertTrue(speech.upstreamFinished.await(3, TimeUnit.SECONDS), "socket close should wake the ACK wait and close the upstream operation");
            assertEquals(0, listener.endedMessages.get(), "a disconnected stream must not report successful completion");
        } finally {
            interviews.delete("voice-test-owner", session.sessionId());
        }
    }

    @Test
    void secondStartIsRejectedWhileTheFirstQuestionStreamIsActive() throws Exception {
        var session = interviews.start("voice-test-owner", "voice-test-material", "resume", "backend", 13,
                (s, prompt) -> question("请介绍项目？"));
        var speech = new AckBlockedSpeechClient();
        var ticket = tickets.issue("voice-test-owner", session.sessionId(), speech);
        var listener = new RecordingListener();
        var socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(wsUri(), listener).get(4, TimeUnit.SECONDS);
        String firstRequestId = UUID.randomUUID().toString();
        String secondRequestId = UUID.randomUUID().toString();
        try {
            sendHello(socket, ticket.voiceSessionId(), ticket.ticket());
            listener.awaitTextType("session.ready");
            String questionId = session.currentQuestion().questionId();
            socket.sendText(mapper.writeValueAsString(mapper.createObjectNode().put("v", 1).put("type", "speech.start")
                    .put("requestId", firstRequestId).put("questionId", questionId)), true).join();
            listener.awaitTextType("speech.started");
            assertTrue(speech.upstreamStarted.await(3, TimeUnit.SECONDS));
            assertNotNull(listener.binary.poll(3, TimeUnit.SECONDS));

            socket.sendText(mapper.writeValueAsString(mapper.createObjectNode().put("v", 1).put("type", "speech.start")
                    .put("requestId", secondRequestId).put("questionId", questionId)), true).join();
            JsonNode rejected = listener.awaitTextType("speech.failed");
            assertEquals(secondRequestId, rejected.path("requestId").asText());
            assertEquals("RATE_LIMITED", rejected.path("code").asText());
            assertEquals(1, speech.upstreamCalls.get(), "the rejected request must not open a second upstream stream");

            socket.sendText(mapper.writeValueAsString(mapper.createObjectNode().put("v", 1).put("type", "speech.cancel")
                    .put("requestId", firstRequestId).put("streamId", 1)), true).join();
            listener.awaitTextType("speech.cancelled");
            assertTrue(speech.upstreamFinished.await(3, TimeUnit.SECONDS));
        } finally {
            if (!socket.isOutputClosed()) socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(2, TimeUnit.SECONDS);
            interviews.delete("voice-test-owner", session.sessionId());
        }
    }

    @Test
    void cancellingAQueuedStreamRemovesItBeforeTheProviderStarts() throws Exception {
        var first = interviews.start("voice-queue-owner-1", "voice-test-material", "resume", "backend", 13,
                (s, prompt) -> question("第一题？"));
        var second = interviews.start("voice-queue-owner-2", "voice-test-material", "resume", "backend", 13,
                (s, prompt) -> question("第二题？"));
        var queued = interviews.start("voice-queue-owner-3", "voice-test-material", "resume", "backend", 13,
                (s, prompt) -> question("第三题？"));
        var firstSpeech = new AckBlockedSpeechClient();
        var secondSpeech = new AckBlockedSpeechClient();
        var queuedSpeech = new AckBlockedSpeechClient();
        var firstTicket = tickets.issue("voice-queue-owner-1", first.sessionId(), firstSpeech);
        var secondTicket = tickets.issue("voice-queue-owner-2", second.sessionId(), secondSpeech);
        var queuedTicket = tickets.issue("voice-queue-owner-3", queued.sessionId(), queuedSpeech);
        var firstListener = new RecordingListener();
        var secondListener = new RecordingListener();
        var queuedListener = new RecordingListener(false);
        var firstSocket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(wsUri(), firstListener).get(4, TimeUnit.SECONDS);
        var secondSocket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(wsUri(), secondListener).get(4, TimeUnit.SECONDS);
        var queuedSocket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(wsUri(), queuedListener).get(4, TimeUnit.SECONDS);
        String firstRequestId = UUID.randomUUID().toString();
        String secondRequestId = UUID.randomUUID().toString();
        String queuedRequestId = UUID.randomUUID().toString();
        try {
            sendHello(firstSocket, firstTicket.voiceSessionId(), firstTicket.ticket());
            sendHello(secondSocket, secondTicket.voiceSessionId(), secondTicket.ticket());
            sendHello(queuedSocket, queuedTicket.voiceSessionId(), queuedTicket.ticket());
            firstListener.awaitTextType("session.ready");
            secondListener.awaitTextType("session.ready");
            queuedListener.requestNext(queuedSocket);
            queuedListener.awaitTextType("session.ready");

            sendStart(firstSocket, firstRequestId, first.currentQuestion().questionId());
            sendStart(secondSocket, secondRequestId, second.currentQuestion().questionId());
            firstListener.awaitTextType("speech.started");
            secondListener.awaitTextType("speech.started");
            assertTrue(firstSpeech.upstreamStarted.await(3, TimeUnit.SECONDS));
            assertTrue(secondSpeech.upstreamStarted.await(3, TimeUnit.SECONDS));
            assertNotNull(firstListener.binary.poll(3, TimeUnit.SECONDS));
            assertNotNull(secondListener.binary.poll(3, TimeUnit.SECONDS));

            sendStart(queuedSocket, queuedRequestId, queued.currentQuestion().questionId());
            // Send cancellation before the client consumes speech.started. The server has queued
            // the operation, but both provider workers remain blocked by the first two streams.
            sendCancel(queuedSocket, queuedRequestId, 1);
            queuedListener.requestNext(queuedSocket);
            assertEquals(queuedRequestId, queuedListener.awaitTextType("speech.started").path("requestId").asText());
            assertEquals(0, queuedSpeech.upstreamCalls.get(), "both provider workers are occupied so this task remains queued");
            queuedListener.requestNext(queuedSocket);
            assertEquals(queuedRequestId, queuedListener.awaitTextType("speech.cancelled").path("requestId").asText());
            assertEquals(0, queuedSpeech.upstreamCalls.get(), "a cancelled queued task must never enter the provider");

            sendCancel(firstSocket, firstRequestId, 1);
            sendCancel(secondSocket, secondRequestId, 1);
            firstListener.awaitTextType("speech.cancelled");
            secondListener.awaitTextType("speech.cancelled");
            assertTrue(firstSpeech.upstreamFinished.await(3, TimeUnit.SECONDS));
            assertTrue(secondSpeech.upstreamFinished.await(3, TimeUnit.SECONDS));
        } finally {
            if (!firstSocket.isOutputClosed()) firstSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(2, TimeUnit.SECONDS);
            if (!secondSocket.isOutputClosed()) secondSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(2, TimeUnit.SECONDS);
            if (!queuedSocket.isOutputClosed()) queuedSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(2, TimeUnit.SECONDS);
            interviews.delete("voice-queue-owner-1", first.sessionId());
            interviews.delete("voice-queue-owner-2", second.sessionId());
            interviews.delete("voice-queue-owner-3", queued.sessionId());
        }
    }

    private void sendStart(WebSocket socket, String requestId, String questionId) throws Exception {
        socket.sendText(mapper.writeValueAsString(mapper.createObjectNode().put("v", 1).put("type", "speech.start")
                .put("requestId", requestId).put("questionId", questionId)), true).join();
    }

    private void sendCancel(WebSocket socket, String requestId, long streamId) throws Exception {
        socket.sendText(mapper.writeValueAsString(mapper.createObjectNode().put("v", 1).put("type", "speech.cancel")
                .put("requestId", requestId).put("streamId", streamId)), true).join();
    }

    private void sendHello(WebSocket socket, String voiceSessionId, String ticket) throws Exception {
        socket.sendText(mapper.writeValueAsString(mapper.createObjectNode().put("v", 1)
                .put("type", "session.hello").put("voiceSessionId", voiceSessionId).put("ticket", ticket)), true).join();
    }

    private URI wsUri() { return URI.create("ws://127.0.0.1:" + port + InterviewVoiceConfiguration.PATH); }

    private final class RecordingListener implements WebSocket.Listener {
        final LinkedBlockingQueue<String> text = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<byte[]> binary = new LinkedBlockingQueue<>();
        final CompletableFuture<Boolean> closed = new CompletableFuture<>();
        final AtomicInteger endedMessages = new AtomicInteger();
        final AtomicInteger terminalMessages = new AtomicInteger();
        private final boolean autoRequest;
        private final StringBuilder pendingText = new StringBuilder();
        RecordingListener() { this(true); }
        RecordingListener(boolean autoRequest) { this.autoRequest = autoRequest; }
        void requestNext(WebSocket webSocket) { webSocket.request(1); }
        @Override public void onOpen(WebSocket webSocket) { if (autoRequest) webSocket.request(1); }
        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            pendingText.append(data);
            if (last) {
                String message = pendingText.toString(); text.add(message);
                if (message.contains("\"type\":\"speech.ended\"")) endedMessages.incrementAndGet();
                if (message.contains("\"type\":\"speech.ended\"") || message.contains("\"type\":\"speech.failed\"")
                        || message.contains("\"type\":\"speech.cancelled\"")) terminalMessages.incrementAndGet();
                pendingText.setLength(0);
            }
            if (autoRequest) webSocket.request(1); return CompletableFuture.completedFuture(null);
        }
        @Override public java.util.concurrent.CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()]; data.get(bytes); binary.add(bytes);
            if (autoRequest) webSocket.request(1); return CompletableFuture.completedFuture(null);
        }
        @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(true); return CompletableFuture.completedFuture(null);
        }
        JsonNode awaitTextType(String type) throws Exception {
            return awaitTextType(type, 4);
        }
        JsonNode awaitTextType(String type, int timeoutSeconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (System.nanoTime() < deadline) {
                String item = text.poll(100, TimeUnit.MILLISECONDS);
                if (item != null) {
                    JsonNode parsed = mapper.readTree(item);
                    if (type.equals(parsed.path("type").asText())) return parsed;
                }
            }
            fail("Timed out waiting for WebSocket message " + type + "; received=" + new ArrayList<>(text));
            return null;
        }
    }

    private static final class FakeStreamingSpeechClient implements StreamingSpeechClient {
        @Override public Transcript transcribe(byte[] wav, String requestId) { throw new UnsupportedOperationException(); }
        @Override public SpeechAudio synthesize(String text, String requestId) { throw new UnsupportedOperationException(); }
        @Override public StreamResult streamQuestion(String text, String requestId, StreamOptions options,
                SpeechCancellation cancellation, PcmSink sink) {
            sink.onFormat(new AudioFormat("pcm_s16le", 24_000, 1));
            sink.onAudio(new byte[]{1, 0, (byte) 0xfe, (byte) 0xff});
            return new StreamResult(2, 4);
        }
    }

    private static final class FailOnceStreamingSpeechClient implements StreamingSpeechClient {
        final AtomicInteger upstreamCalls = new AtomicInteger();
        @Override public Transcript transcribe(byte[] wav, String requestId) { throw new UnsupportedOperationException(); }
        @Override public SpeechAudio synthesize(String text, String requestId) { throw new UnsupportedOperationException(); }
        @Override public StreamResult streamQuestion(String text, String requestId, StreamOptions options,
                SpeechCancellation cancellation, PcmSink sink) {
            if (upstreamCalls.incrementAndGet() == 1) throw new InterviewException(502, "上游测试失败");
            sink.onFormat(new AudioFormat("pcm_s16le", 24_000, 1));
            sink.onAudio(new byte[]{1, 0, 2, 0});
            return new StreamResult(2, 4);
        }
    }

    private static final class AckBlockedSpeechClient implements StreamingSpeechClient {
        final CountDownLatch upstreamStarted = new CountDownLatch(1);
        final CountDownLatch upstreamFinished = new CountDownLatch(1);
        final AtomicInteger upstreamCalls = new AtomicInteger();
        @Override public Transcript transcribe(byte[] wav, String requestId) { throw new UnsupportedOperationException(); }
        @Override public SpeechAudio synthesize(String text, String requestId) { throw new UnsupportedOperationException(); }
        @Override public StreamResult streamQuestion(String text, String requestId, StreamOptions options,
                SpeechCancellation cancellation, PcmSink sink) {
            upstreamCalls.incrementAndGet();
            sink.onFormat(new AudioFormat("pcm_s16le", 24_000, 1));
            upstreamStarted.countDown();
            try {
                sink.onAudio(new byte[InterviewVoiceProtocol.MAX_BUFFERED_SAMPLES * 2 + 2]);
                return new StreamResult(InterviewVoiceProtocol.MAX_BUFFERED_SAMPLES + 1L,
                        (InterviewVoiceProtocol.MAX_BUFFERED_SAMPLES + 1L) * 2L);
            } finally { upstreamFinished.countDown(); }
        }
    }
}
