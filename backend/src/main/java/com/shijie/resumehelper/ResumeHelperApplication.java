package com.shijie.resumehelper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 拾阶求职工作台 · Java 后端入口（模块化单体）。
 *
 * <p>各业务模块通过包划分，便于多人并行开发：
 * <ul>
 *   <li>{@code com.shijie.resumehelper.job} —— 岗位来源与匹配（功能二，本模块已实现）</li>
 *   <li>队友后续加入：用户与个人资料、材料解析、简历生成与版本、面试会话与反馈</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ResumeHelperApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResumeHelperApplication.class, args);
    }
}
