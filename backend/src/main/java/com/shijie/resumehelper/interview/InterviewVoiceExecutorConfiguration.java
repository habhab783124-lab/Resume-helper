package com.shijie.resumehelper.interview;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class InterviewVoiceExecutorConfiguration {
    @Bean(name = "interviewVoiceExecutor", destroyMethod = "shutdownNow")
    public ThreadPoolExecutor interviewVoiceExecutor() {
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), runnable -> {
            Thread thread = new Thread(runnable, "interview-voice-tts");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
}
