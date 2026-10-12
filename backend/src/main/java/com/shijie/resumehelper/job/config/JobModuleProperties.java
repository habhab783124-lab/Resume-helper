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
    private final Ai ai = new Ai();
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

    public Ai getAi() {
        return ai;
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

    /** 大模型配置。 */
    public static class Ai {
        /** OpenAI 兼容端点。DeepSeek / 通义 / 豆包 / 自建网关都按这个协议接。 */
        private String baseUrl = "https://api.deepseek.com/v1";
        private String model = "deepseek-chat";
        /**
         * 服务端兜底密钥。留空表示必须由前端携带用户自己的 token。
         * <p>产品方案 5 要求「密钥放在后端配置中，不能写进前端代码」；
         * 而实际需求是「让用户填自己的 token」——两者靠下面的优先级共存：
         * 请求头里的用户 token 优先，没有再回落到这里的服务端密钥。
         */
        private String apiKey = "";
        private int timeoutSeconds = 60;
        private int maxTokens = 800;
        private double temperature = 0.2;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
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
