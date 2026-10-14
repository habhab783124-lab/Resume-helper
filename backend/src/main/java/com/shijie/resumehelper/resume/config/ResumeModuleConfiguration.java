package com.shijie.resumehelper.resume.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.resume.service.ResumeService;
import com.shijie.resumehelper.resume.store.FileSystemResumeVersionStore;
import com.shijie.resumehelper.resume.store.ResumeVersionStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 简历模块的装配。
 *
 * <p>和岗位 / 材料模块一样：业务类不用 {@code @Component}，全部在这里显式 new，
 * 让 {@code domain} / {@code service} 保持零框架依赖，能脱离 Spring 跑自检。
 */
@Configuration
@EnableConfigurationProperties(ResumeModuleProperties.class)
public class ResumeModuleConfiguration {

    @Bean
    public ResumeVersionStore resumeVersionStore(ResumeModuleProperties properties, ObjectMapper mapper) {
        return new FileSystemResumeVersionStore(properties, mapper);
    }

    @Bean
    public ResumeService resumeService(ResumeVersionStore store, ResumeModuleProperties properties) {
        return new ResumeService(store, properties);
    }
}
