package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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

    /** 单一时钟 bean，便于 T03/T08 的持久化时间戳与可注入测试时钟统一。 */
    @Bean
    public Clock interviewClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    public InterviewGenerationRunner interviewGenerationRunner(InterviewService service,
                                                               InterviewGenerationCoordinator coordinator,
                                                               InterviewProperties properties) {
        var executor = new ThreadPoolExecutor(properties.getGenerationWorkers(), properties.getGenerationWorkers(),
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(properties.getGenerationQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "interview-generation");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        return new InterviewGenerationRunner(service, coordinator, executor);
    }
}
