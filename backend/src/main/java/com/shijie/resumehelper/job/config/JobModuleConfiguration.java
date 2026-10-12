package com.shijie.resumehelper.job.config;

import com.shijie.resumehelper.job.agent.JobSearchAgent;
import com.shijie.resumehelper.job.match.MatchScorer;
import com.shijie.resumehelper.job.source.CsvJobSource;
import com.shijie.resumehelper.job.source.JobImporter;
import com.shijie.resumehelper.job.source.JobSource;
import com.shijie.resumehelper.job.source.JobSourceRegistry;
import com.shijie.resumehelper.job.source.MockJobSource;
import com.shijie.resumehelper.job.store.InMemoryJobStore;
import com.shijie.resumehelper.job.store.JobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 岗位模块的 Spring 装配。
 *
 * <p>设计原则：<b>所有业务逻辑都在 core 层，这里只做装配和外部资源接入</b>。
 * core 层（domain / source / match / agent / ai 接口）不依赖 Spring，
 * 因此可以用 {@code JobAgentOfflineDemo} 脱离容器直接验证，
 * 队友改 Spring 配置也不会影响逻辑正确性。
 *
 * <p>新增岗位来源只需实现 {@link JobSource} 并注册成 Spring Bean，
 * 智能体和检索服务都不需要改动（这就是「可插拔多源」的落地点）。
 */
@Configuration
public class JobModuleConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JobModuleConfiguration.class);

    @Bean
    public JobStore jobStore() {
        return new InMemoryJobStore();
    }

    @Bean
    public MatchScorer matchScorer() {
        return new MatchScorer();
    }

    @Bean
    public JobImporter jobImporter() {
        return new JobImporter();
    }

    /** 内置演示岗位库：兜底来源，保证岗位页永远有内容。 */
    @Bean
    @ConditionalOnProperty(prefix = "app.job.sources", name = "mock-enabled",
            havingValue = "true", matchIfMissing = true)
    public JobSource mockJobSource() {
        return new MockJobSource();
    }

    /** 人工整理的 CSV 岗位库。见 src/main/resources/jobs/sample-jobs.csv 的表头说明。 */
    @Bean
    @ConditionalOnProperty(prefix = "app.job.sources", name = "csv-enabled",
            havingValue = "true", matchIfMissing = true)
    public JobSource csvJobSource(JobModuleProperties properties) {
        String location = properties.getSources().getCsvLocation();
        log.info("CSV 岗位库位置：{}", location);
        return new CsvJobSource("CSV 岗位库", location, true);
    }

    /**
     * 数据源注册中心。Spring 会把上面所有 {@link JobSource} Bean 注入进来，
     * 队友新增来源不需要改这里。
     */
    @Bean
    public JobSourceRegistry jobSourceRegistry(List<JobSource> sources) {
        log.info("已装配 {} 个岗位来源：{}", sources.size(),
                sources.stream().map(JobSource::name).toList());
        return new JobSourceRegistry(sources);
    }

    @Bean
    public JobSearchAgent jobSearchAgent(JobSourceRegistry registry, MatchScorer scorer) {
        return new JobSearchAgent(registry, scorer);
    }
}
