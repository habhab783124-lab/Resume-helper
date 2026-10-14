package com.shijie.resumehelper.common.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 大模型配置的注册。
 *
 * <h2>为什么需要这个类</h2>
 * {@link AiProperties} 原来叫 {@code JobModuleProperties.Ai}，是跟着岗位模块的
 * {@code @EnableConfigurationProperties(JobModuleProperties.class)} 一起注册的。
 * 把它提升成独立的 {@code app.ai} 之后，如果不显式注册，
 * Spring 就找不到这个 Bean，{@link AiClientResolver} 注入失败，
 * <b>整个上下文起不来</b>。
 *
 * <p>这类"搬家漏了注册"的问题在编译期发现不了，只有真正启动应用才会暴露——
 * 所以 {@code InterviewApiTest} 这类会加载完整 Spring 上下文的测试很有价值。
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {
}
