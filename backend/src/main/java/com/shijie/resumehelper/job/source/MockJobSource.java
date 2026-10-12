package com.shijie.resumehelper.job.source;

import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.domain.JobQuery;

import java.time.Instant;
import java.util.List;

/**
 * 内置演示岗位库（兜底来源）。
 *
 * <p>对应产品方案 3.C：「同时内置一小批演示岗位，确保外部站点暂时不可用时，岗位匹配功能仍然可以展示」，
 * 以及 11 的兜底策略「外部岗位源暂时不可用时，演示岗位库仍能完成流程」。
 *
 * <p>这里的岗位是<b>人工编造的演示数据</b>，不代表任何真实企业的在招职位，
 * 也不用于对外展示。真实部署时应替换为公开来源或学校提供的岗位表。
 *
 * <p>本来源故意不做关键词过滤，交给 {@link JobSourceRegistry} 统一处理，
 * 这样关键词过窄时注册中心能触发「放宽为硬条件」的降级。
 */
public final class MockJobSource implements JobSource {

    /** 演示来源的名称。合并去重时靠它识别「这是兜底数据」，真实来源优先。 */
    public static final String SOURCE_NAME = "演示岗位库";

    private static final Instant COLLECTED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final List<Job> JOBS = List.of(
            job("Java 后端开发工程师", "璞云科技", "上海", "徐汇", "15-22K", "本科", "应届 / 1 年以内", "校招",
                    List.of("Java", "Spring Boot", "MySQL"), "负责企业内部系统的后端接口开发与维护，参与需求评审、"
                            + "数据库表设计和接口联调。技术栈为 Java + Spring Boot + MySQL，需要熟悉常用数据结构，"
                            + "有 Git 协作经验。"),
            job("后端研发工程师（校招）", "新界数科", "上海", "浦东", "16-25K", "本科", "应届校招", "校招",
                    List.of("Java", "Redis", "微服务"), "参与公司核心交易链路的后端研发，方向包括服务拆分、"
                            + "缓存设计与接口性能优化。要求掌握 Java 并发编程，了解 Redis 和微服务的基本原理。"),
            job("软件开发工程师", "澄海智能", "上海", "杨浦", "14-20K", "本科", "应届 / 1 年以内", "校招",
                    List.of("Java", "SQL", "Spring"), "参与智能硬件配套管理平台的开发，负责业务模块的编码、"
                            + "SQL 编写和问题排查。要求熟悉 Java 与 Spring 框架，具备良好的编码规范意识。"),
            job("Java 服务端研发", "林序信息", "上海", "静安", "18-26K", "本科", "1-3 年", "全职",
                    List.of("Java", "MySQL", "分布式"), "负责高并发场景下的服务端研发，参与分布式系统的设计与治理，"
                            + "包括限流、降级和链路追踪。要求熟悉 JVM 调优与 MySQL 索引优化。"),
            job("初级后端工程师", "方寸云联", "上海", "长宁", "13-19K", "大专", "应届 / 经验不限", "校招",
                    List.of("Spring Boot", "Redis", "Linux"), "在导师带领下完成后端功能开发，参与接口文档编写与"
                            + "线上问题跟进。要求了解 Spring Boot、Redis 和 Linux 常用命令，学习能力强即可。"),
            job("研发工程师 — 平台方向", "知行数据", "上海", "虹口", "16-24K", "本科", "应届校招", "校招",
                    List.of("Java", "MySQL", "数据结构"), "参与数据平台的服务端开发，负责接口实现、单元测试与"
                            + "性能压测。要求数据结构与算法基础扎实，熟悉 MySQL。"),
            job("Java 开发工程师", "青屿网络", "上海", "闵行", "15-23K", "本科", "1 年以内", "全职",
                    List.of("Java", "Spring", "消息队列"), "负责订单与结算系统的后端开发，使用消息队列做异步解耦。"
                            + "要求熟悉 Java、Spring，了解消息队列的基本使用与可靠性投递。"),
            job("业务后端开发", "未央科技", "上海", "徐汇", "14-21K", "本科", "应届 / 1 年以内", "校招",
                    List.of("Java", "RESTful", "Docker"), "负责业务中台接口的设计与实现，参与容器化部署流程。"
                            + "要求熟悉 Java 与 RESTful 接口设计，了解 Docker 基本操作。"),
            job("后端开发实习生", "潮汐云", "上海", "普陀", "4-6K", "本科", "在校 / 实习", "实习",
                    List.of("Java", "Spring Boot"), "参与内部工具的后端开发，每周到岗至少 3 天，可长期实习。"
                            + "要求掌握 Java 基础与 Spring Boot，有课程项目经验即可。"),
            job("Java 开发工程师（数据方向）", "明澈科技", "上海", "浦东", "17-25K", "本科", "应届校招", "校招",
                    List.of("Java", "Hadoop", "Spark"), "参与离线与实时数据处理任务的开发，使用 Java 对接大数据组件。"
                            + "要求熟悉 Java，了解 Hadoop 或 Spark 的基本使用。"),
            job("全栈开发工程师", "星澜网络", "上海", "徐汇", "15-22K", "本科", "应届 / 1 年以内", "校招",
                    List.of("Java", "Vue", "MySQL"), "负责中小型项目的前后端开发，前端使用 Vue，后端使用 Java。"
                            + "要求至少一端扎实，另一端能配合完成开发。"),
            job("Java 后端开发", "京畿互联", "北京", "海淀", "18-28K", "本科", "应届校招", "校招",
                    List.of("Java", "Spring Boot", "Kafka"), "参与广告投放系统的后端研发，处理高并发写入与"
                            + "实时数据流。要求熟悉 Java 与 Spring Boot，了解 Kafka。"),
            job("后端研发工程师", "云栖数据", "杭州", "余杭", "16-24K", "本科", "应届 / 1 年以内", "校招",
                    List.of("Java", "Redis", "MySQL"), "参与 SaaS 产品的后端研发，负责租户隔离、权限与计费模块。"
                            + "要求熟悉 Java、MySQL 与 Redis，具备良好的抽象能力。")
    );

    private static Job job(String title, String company, String city, String district, String salary,
                           String education, String experience, String jobType, List<String> skills,
                           String description) {
        return Job.builder()
                .title(title)
                .company(company)
                .city(city)
                .district(district)
                .salary(salary)
                .education(education)
                .experience(experience)
                .jobType(jobType)
                .skills(skills)
                .description(description)
                .source(SOURCE_NAME, "")
                .collectedAt(COLLECTED_AT)
                .build();
    }

    @Override
    public String name() {
        return SOURCE_NAME;
    }

    @Override
    public String kind() {
        return "mock";
    }

    @Override
    public String description() {
        return "内置演示数据，外部来源不可用时兜底（非真实在招岗位）";
    }

    @Override
    public List<Job> search(JobQuery query) {
        return JOBS.stream().filter(query::accepts).toList();
    }

    /** 演示库的全部岗位，供前端「来源说明」展示条数。 */
    public List<Job> all() {
        return JOBS;
    }
}
