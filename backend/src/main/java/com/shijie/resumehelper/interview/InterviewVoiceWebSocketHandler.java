package com.shijie.resumehelper.interview;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;

@Component
public final class InterviewVoiceWebSocketHandler extends AbstractWebSocketHandler {
    private final InterviewVoiceStreamService streams;

    public InterviewVoiceWebSocketHandler(InterviewVoiceStreamService streams) { this.streams = streams; }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        streams.open(new ConcurrentWebSocketSessionDecorator(session, 10_000, 16_384));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (message.getPayloadLength() > 8192) {
            closeQuietly(session, new CloseStatus(1009, "message too large"));
            return;
        }
        streams.onText(session.getId(), message.getPayload());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        streams.onBinary(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        streams.close(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        streams.close(session.getId());
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try { if (session.isOpen()) session.close(status); } catch (IOException ignored) { }
    }
}
