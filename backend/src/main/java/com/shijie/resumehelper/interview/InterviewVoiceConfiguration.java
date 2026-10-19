package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.job.config.JobModuleProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.List;

@Configuration
@EnableWebSocket
public class InterviewVoiceConfiguration implements WebSocketConfigurer {
    public static final String PATH = "/api/interview-voice/ws";
    private final InterviewVoiceWebSocketHandler handler;
    private final List<String> allowedOrigins;

    public InterviewVoiceConfiguration(InterviewVoiceWebSocketHandler handler, JobModuleProperties jobProperties) {
        this.handler = handler;
        this.allowedOrigins = List.copyOf(jobProperties.getCors().getAllowedOriginPatterns());
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, PATH).setAllowedOriginPatterns(allowedOrigins.toArray(String[]::new));
    }

    @Bean
    public ServletServerContainerFactoryBean interviewVoiceWebSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(8192);
        container.setMaxBinaryMessageBufferSize(InterviewVoiceProtocol.HEADER_BYTES + InterviewVoiceProtocol.MAX_PCM_BYTES);
        container.setMaxSessionIdleTimeout(60_000L);
        return container;
    }

}
