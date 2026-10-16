package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 面试模块的存储装配。{@link InterviewProperties} 由应用入口的
 * {@code @ConfigurationPropertiesScan} 注册（与 {@link SpeechProperties} 同方式）。
 */
@Configuration
public class InterviewConfiguration {

    @Bean
    public InterviewStore interviewStore(InterviewProperties properties, ObjectMapper objectMapper) {
        return new FileSystemInterviewStore(Path.of(properties.getStorageDir()), objectMapper);
    }
}
