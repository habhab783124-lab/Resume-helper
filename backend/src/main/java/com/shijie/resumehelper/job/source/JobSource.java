package com.shijie.resumehelper.job.source;

import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.domain.JobQuery;

import java.util.List;

/**
 * 岗位数据源 SPI。
 * <p>产品方案要求「岗位来源先控制为一个可验证的公开来源，同时内置演示岗位库兜底」。
 * 这里把「来源」抽象成可插拔接口，新增一个来源只要实现本接口并注册为 Spring Bean 即可，
 * 智能体和检索服务都不需要改动。
 *
 * <p>实现约定：
 * <ul>
 *   <li>只读取免登录的公开页面或公开 API；<b>不绕过登录、不绕过反爬</b>。</li>
 *   <li>单个来源失败必须抛 {@link JobSourceException}，由注册中心隔离，不能拖垮整次检索。</li>
 *   <li>返回的岗位必须带上 sourceName / sourceUrl / collectedAt，保证可回溯。</li>
 * </ul>
 */
public interface JobSource {

    /** 数据源名称，会展示在前端岗位卡片的来源位置。 */
    String name();

    /** 数据源类型：mock / csv / public-api / public-web / search / paste。 */
    default String kind() {
        return "public-api";
    }

    /** 是否启用。可通过配置关闭不稳定来源。 */
    default boolean enabled() {
        return true;
    }

    /** 该来源支持的能力，例如是否需要联网。仅用于前端展示「来源可用性」。 */
    default String description() {
        return name();
    }

    /**
     * 按条件检索岗位。
     *
     * @param query 检索条件，实现方应尽量使用 query.allKeywords() 与硬筛选条件
     * @return 岗位列表，允许为空，不允许返回 null
     */
    List<Job> search(JobQuery query);

    /** 数据源不可用或返回异常时抛出，由注册中心捕获并降级。 */
    class JobSourceException extends RuntimeException {
        private final String sourceName;

        public JobSourceException(String sourceName, String message) {
            super(message);
            this.sourceName = sourceName;
        }

        public JobSourceException(String sourceName, String message, Throwable cause) {
            super(message, cause);
            this.sourceName = sourceName;
        }

        public String sourceName() {
            return sourceName;
        }
    }
}
