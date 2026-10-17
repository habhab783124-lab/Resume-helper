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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.shijie.resumehelper.interview.InterviewTestFixtures.question;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.interview.voice.enabled=true")
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

    private URI wsUri() { return URI.create("ws://127.0.0.1:" + port + InterviewVoiceConfiguration.PATH); }

    private final class RecordingListener implements WebSocket.Listener {
        final LinkedBlockingQueue<String> text = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<byte[]> binary = new LinkedBlockingQueue<>();
        final CompletableFuture<Boolean> closed = new CompletableFuture<>();
        final AtomicInteger endedMessages = new AtomicInteger();
        private final StringBuilder pendingText = new StringBuilder();
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            pendingText.append(data);
            if (last) {
                String message = pendingText.toString(); text.add(message);
                if (message.contains("\"type\":\"speech.ended\"")) endedMessages.incrementAndGet();
                pendingText.setLength(0);
            }
            webSocket.request(1); return CompletableFuture.completedFuture(null);
        }
        @Override public java.util.concurrent.CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()]; data.get(bytes); binary.add(bytes);
            webSocket.request(1); return CompletableFuture.completedFuture(null);
        }
        @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(true); return CompletableFuture.completedFuture(null);
        }
        JsonNode awaitTextType(String type) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
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
}
