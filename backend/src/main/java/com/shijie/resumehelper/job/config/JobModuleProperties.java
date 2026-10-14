package com.shijie.resumehelper.job.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 岗位模块配置，对应 application.yml 的 {@code app.job.*}。
 */
@ConfigurationProperties(prefix = "app.job")
public class JobModuleProperties {

    /** 智能体最多检索几轮。 */
    private int maxRounds = 3;
    /** 默认返回岗位条数。 */
    private int defaultLimit = 12;
    /** 前 N 个岗位调用大模型生成匹配解释，0 表示不调用。 */
    private int explainTopN = 5;
    /** 单次请求最多返回条数，防止前端传入超大 limit 拖垮服务。 */
    private int maxLimit = 50;

    private final Sources sources = new Sources();
    private final Cors cors = new Cors();

    public int getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public int getExplainTopN() {
        return explainTopN;
    }

    public void setExplainTopN(int explainTopN) {
        this.explainTopN = explainTopN;
    }

    public int getMaxLimit() {
        return maxLimit;
    }

    public void setMaxLimit(int maxLimit) {
        this.maxLimit = maxLimit;
    }

    public Sources getSources() {
        return sources;
    }

    /** 岗位数据源开关。 */
    public static class Sources {
        /** 内置演示岗位库。关掉后如果外部来源都不可用，岗位页会变空。 */
        private boolean mockEnabled = true;
        /** 是否启用 CSV 岗位库。 */
        private boolean csvEnabled = true;
        /** CSV 位置：{@code classpath:xxx.csv} 或绝对路径。 */
        private String csvLocation = "classpath:jobs/sample-jobs.csv";

        public boolean isMockEnabled() {
            return mockEnabled;
        }

        public void setMockEnabled(boolean mockEnabled) {
            this.mockEnabled = mockEnabled;
        }

        public boolean isCsvEnabled() {
            return csvEnabled;
        }

        public void setCsvEnabled(boolean csvEnabled) {
            this.csvEnabled = csvEnabled;
        }

        public String getCsvLocation() {
            return csvLocation;
        }

        public void setCsvLocation(String csvLocation) {
            this.csvLocation = csvLocation;
        }
    }

    public Cors getCors() {
        return cors;
    }

    /**
     * 跨域白名单。
     *
     * <p>为什么需要它：本地开发时前端是 {@code localhost:5173}，后端是 {@code localhost:8080}，
     * 走 vite 代理就没有跨域问题；但**前端一旦部署到 Vercel 之类的公网域名**，
     * 浏览器就会用这个域名发起请求，必须把它加进白名单，否则预检直接失败。
     *
     * <p>用 {@code allowedOriginPatterns} 而不是 {@code allowedOrigins}：
     * 前者支持通配符（例如 {@code https://*.vercel.app}），
     * 而且能与 {@code allowCredentials(true)} 共存。
     */
    public static class Cors {
        private List<String> allowedOriginPatterns = new ArrayList<>(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*"));

        public List<String> getAllowedOriginPatterns() {
            return allowedOriginPatterns;
        }

        public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
            this.allowedOriginPatterns = allowedOriginPatterns == null
                    ? new ArrayList<>()
                    : new ArrayList<>(allowedOriginPatterns);
        }
    }
}
