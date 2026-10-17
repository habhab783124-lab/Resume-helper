package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.material.service.MaterialService;
import com.shijie.resumehelper.resume.service.ResumeService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

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
@EnableScheduling
public class InterviewConfiguration {

    @Bean
    public InterviewSourceResolver interviewSourceResolver(MaterialService materials, ResumeService resumes) {
        return new InterviewSourceResolver(materials, resumes);
    }

    @Bean(destroyMethod = "shutdownNow")
    public ThreadPoolExecutor interviewPlanExecutor(InterviewProperties properties) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getGenerationQueueCapacity()), runnable -> {
                    Thread thread = new Thread(runnable, "interview-plan");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public InterviewPlanService interviewPlanService(InterviewStore store, InterviewSourceResolver sources,
                                                     InterviewAgent agent, InterviewService interviews,
                                                     InterviewGenerationCoordinator generations,
                                                     InterviewGenerationRunner runner,
                                                     ThreadPoolExecutor interviewPlanExecutor,
                                                     Clock interviewClock) {
        var starter = new InterviewPlanService.PlanStartHandler() {
            @Override public InterviewService.Session start(String owner, InterviewSourceResolver.ResolvedSource source,
                    InterviewPlan plan, String requestId, com.shijie.resumehelper.common.ai.AiClient ai) {
                return start(owner, source, plan, requestId, ai, false);
            }
            @Override public InterviewService.Session start(String owner, InterviewSourceResolver.ResolvedSource source,
                    InterviewPlan plan, String requestId, com.shijie.resumehelper.common.ai.AiClient ai, boolean allowAlgorithmRepeat) {
                var session = interviews.startAsync(owner, source, plan, requestId, allowAlgorithmRepeat);
                var generation = session.generation();
                if (generation != null && generation.state() == InterviewSnapshot.GenerationState.QUEUED) {
                    var claim = generations.claim(owner, session.sessionId(), generation.requestId(),
                            InterviewSnapshot.GenerationKind.OPENING, session.revision());
                    runner.start(claim, ai);
                }
                return interviews.get(owner, session.sessionId());
            }
            @Override public InterviewService.Session get(String owner, String sessionId) {
                return interviews.get(owner, sessionId);
            }
            @Override public void validatePracticeSource(String owner, String sessionId,
                    java.util.List<InterviewPlan.PracticeTarget> targets) {
                interviews.validatePracticeSource(owner, sessionId, targets);
            }
        };
        return new InterviewPlanService(store, sources::resolve, agent::preparePlan,
                interviewPlanExecutor, interviewClock, starter);
    }

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
