package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StreamingSpeechClientTest {
    private HttpServer server;
    private ExecutorService worker;
    private SpeechProperties properties;
    private VolcengineSpeechClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        worker = Executors.newCachedThreadPool();
        server.setExecutor(worker);
        properties = new SpeechProperties();
        properties.setApiKey("test-only-key");
        properties.setTtsTimeoutSeconds(5);
        client = new VolcengineSpeechClient(properties, mapper,
                "http://127.0.0.1:" + server.getAddress().getPort(), HttpClient.newHttpClient());
        server.start();
    }

    @AfterEach
    void cleanup() { server.stop(0); worker.shutdownNow(); }

    @Test
    void streamsPcmToSinkBeforeUpstreamTerminalAndCarriesOddBytesAcrossParts() throws Exception {
        CountDownLatch firstAudioSent = new CountDownLatch(1);
        CountDownLatch allowTerminal = new CountDownLatch(1);
        server.createContext("/api/v3/tts/unidirectional", exchange -> {
            assertPcmRequest(exchange);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                writeSplitPart(out, 0, new byte[]{1, 2, 3});
                firstAudioSent.countDown();
                allowTerminal.await(2, TimeUnit.SECONDS);
                writePart(out, 0, new byte[]{4, 5});
                writePart(out, 20000000, new byte[]{6});
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        var cancellation = new SpeechCancellation();
        List<byte[]> chunks = new ArrayList<>();
        CountDownLatch sinkReached = new CountDownLatch(1);
        AtomicReference<StreamingSpeechClient.StreamResult> result = new AtomicReference<>();
        var task = worker.submit(() -> result.set(client.streamQuestion("题目", "req-stream-1",
                new StreamingSpeechClient.StreamOptions(2, 2, 5, 1024), cancellation,
                new StreamingSpeechClient.PcmSink() {
                    public void onFormat(StreamingSpeechClient.AudioFormat format) {
                        assertEquals(new StreamingSpeechClient.AudioFormat("pcm_s16le", 24000, 1), format);
                    }
                    public void onAudio(byte[] pcm) { chunks.add(pcm); sinkReached.countDown(); }
                })));
        assertTrue(firstAudioSent.await(1, TimeUnit.SECONDS));
        assertTrue(sinkReached.await(1, TimeUnit.SECONDS), "audio must reach the sink before upstream end");
        allowTerminal.countDown();
        task.get(3, TimeUnit.SECONDS);
        assertArrayEquals(new byte[]{1, 2}, chunks.get(0));
        assertArrayEquals(new byte[]{3, 4}, chunks.get(1));
        assertArrayEquals(new byte[]{5, 6}, chunks.get(2));
        assertEquals(3, result.get().totalSamples());
        assertEquals(6, result.get().totalBytes());
    }

    @Test
    void cancellationFromSinkClosesAndStopsTheUpstreamStream() throws Exception {
        CountDownLatch requestSeen = new CountDownLatch(1);
        server.createContext("/api/v3/tts/unidirectional", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            requestSeen.countDown();
            try (var out = exchange.getResponseBody()) {
                writePart(out, 0, new byte[]{1, 2});
                Thread.sleep(5000);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException ignored) { }
        });
        SpeechCancellation cancellation = new SpeechCancellation();
        var task = worker.submit(() -> assertThrows(java.util.concurrent.CancellationException.class, () -> client.streamQuestion("题目", "req-cancel",
                new StreamingSpeechClient.StreamOptions(3, 3, 8, 1024), cancellation, new StreamingSpeechClient.PcmSink() {
                    public void onFormat(StreamingSpeechClient.AudioFormat format) { }
                    public void onAudio(byte[] pcm) { cancellation.cancel(); }
                })));
        assertTrue(requestSeen.await(1, TimeUnit.SECONDS));
        task.get(2, TimeUnit.SECONDS);
    }

    @Test
    void cancellationWhileReadingAnOpenUpstreamBodyUnblocksTheReader() throws Exception {
        CountDownLatch requestSeen = new CountDownLatch(1);
        CountDownLatch firstAudioDelivered = new CountDownLatch(1);
        server.createContext("/api/v3/tts/unidirectional", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            requestSeen.countDown();
            try (var out = exchange.getResponseBody()) {
                writePart(out, 0, new byte[]{1, 2});
                Thread.sleep(5000);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException ignored) { }
        });
        SpeechCancellation cancellation = new SpeechCancellation();
        var task = worker.submit(() -> assertThrows(java.util.concurrent.CancellationException.class,
                () -> client.streamQuestion("题目", "req-cancel-blocked-read",
                        new StreamingSpeechClient.StreamOptions(3, 3, 8, 1024), cancellation,
                        new StreamingSpeechClient.PcmSink() {
                            public void onFormat(StreamingSpeechClient.AudioFormat format) { }
                            public void onAudio(byte[] pcm) { firstAudioDelivered.countDown(); }
                        })));
        assertTrue(requestSeen.await(1, TimeUnit.SECONDS));
        assertTrue(firstAudioDelivered.await(1, TimeUnit.SECONDS));
        cancellation.cancel();
        task.get(2, TimeUnit.SECONDS);
    }

    @Test
    void emptyAudioWithSuccessfulTerminalIsRejected() throws Exception {
        server.createContext("/api/v3/tts/unidirectional", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                writePart(out, 0, new byte[0]);
                writePart(out, 20000000, new byte[0]);
            }
        });
        assertThrows(InterviewException.class, () -> client.streamQuestion("题目", "req-empty-audio",
                new StreamingSpeechClient.StreamOptions(2, 2, 5, 1024), new SpeechCancellation(), emptySink()));
    }

    @Test
    void malformedJsonIsRejected() throws Exception {
        server.createContext("/api/v3/tts/unidirectional", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write("{broken json}\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        assertThrows(InterviewException.class, () -> client.streamQuestion("题目", "req-bad-json",
                new StreamingSpeechClient.StreamOptions(2, 2, 5, 1024), new SpeechCancellation(), emptySink()));
    }

    @Test
    void providerBusinessFailureIsRejected() throws Exception {
        server.createContext("/api/v3/tts/unidirectional", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) { writePart(out, 45000292, new byte[0]); }
        });
        assertThrows(InterviewException.class, () -> client.streamQuestion("题目", "req-business-error",
                new StreamingSpeechClient.StreamOptions(2, 2, 5, 1024), new SpeechCancellation(), emptySink()));
    }

    @Test
    void audioLimitIsEnforcedAcrossProviderParts() throws Exception {
        server.createContext("/api/v3/tts/unidirectional", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                writePart(out, 0, new byte[]{1, 2});
                writePart(out, 20000000, new byte[]{3, 4});
            }
        });
        assertThrows(InterviewException.class, () -> client.streamQuestion("题目", "req-audio-limit",
                new StreamingSpeechClient.StreamOptions(2, 2, 5, 2), new SpeechCancellation(), emptySink()));
    }

    @Test
    void eofBeforeSuccessAndOddFinalByteAreRejected() throws Exception {
        server.createContext("/api/v3/tts/unidirectional", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) { writePart(out, 0, new byte[]{1, 2, 3}); }
        });
        assertThrows(InterviewException.class, () -> client.streamQuestion("题目", "req-eof",
                new StreamingSpeechClient.StreamOptions(2, 2, 5, 1024), new SpeechCancellation(), emptySink()));
    }


    @Test
    void cancellationBeforeResponseHeadersPreventsLateBodyFromStarting() throws Exception {
        CountDownLatch requestSeen = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.createContext("/api/v3/tts/unidirectional", exchange -> {
            requestSeen.countDown();
            try {
                releaseResponse.await(2, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, 0);
                try (var out = exchange.getResponseBody()) {
                    writePart(out, 0, new byte[]{1, 2});
                    writePart(out, 20000000, new byte[0]);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) { }
        });
        SpeechCancellation cancellation = new SpeechCancellation();
        var task = worker.submit(() -> assertThrows(java.util.concurrent.CancellationException.class,
                () -> client.streamQuestion("题目", "req-cancel-before-body",
                        new StreamingSpeechClient.StreamOptions(2, 2, 5, 1024), cancellation, emptySink())));
        assertTrue(requestSeen.await(1, TimeUnit.SECONDS));
        cancellation.cancel();
        releaseResponse.countDown();
        task.get(2, TimeUnit.SECONDS);
    }

    @Test
    void cancellationCallbackIsImmediateForLateRegistrationAndCanBeRemoved() {
        SpeechCancellation cancellation = new SpeechCancellation();
        cancellation.cancel();
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        cancellation.onCancel(() -> called.set(true));
        assertTrue(called.get());
        assertThrows(java.util.concurrent.CancellationException.class, cancellation::throwIfCancelled);
    }

    private static StreamingSpeechClient.PcmSink emptySink() {
        return new StreamingSpeechClient.PcmSink() {
            public void onFormat(StreamingSpeechClient.AudioFormat format) { }
            public void onAudio(byte[] pcm) { }
        };
    }
    private static void assertPcmRequest(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"format\":\"pcm\""));
        assertTrue(body.contains("\"sample_rate\":24000"));
        assertEquals("test-only-key", exchange.getRequestHeaders().getFirst("X-Api-Key"));
    }
    private static void writePart(java.io.OutputStream out, int code, byte[] bytes) throws IOException {
        String data = bytes.length == 0 ? "null" : "\"" + Base64.getEncoder().encodeToString(bytes) + "\"";
        out.write(("{\"code\":" + code + ",\"data\":" + data + "}\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
    private static void writeSplitPart(java.io.OutputStream out, int code, byte[] bytes) throws IOException {
        String data = bytes.length == 0 ? "null" : "\"" + Base64.getEncoder().encodeToString(bytes) + "\"";
        byte[] json = ("{\"code\":" + code + ",\"message\":\"音频分片\",\"data\":" + data + "}\n").getBytes(StandardCharsets.UTF_8);
        for (byte value : json) { out.write(value); out.flush(); }
    }
}
