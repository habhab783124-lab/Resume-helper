package com.shijie.resumehelper.common.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 大模型配置，对应 application.yml 的 {@code app.ai.*}。
 *
 * <p><b>为什么放在 common 而不是某个业务模块下</b>：它是被多个模块共用的基础设施——
 * 岗位模块用它生成匹配解释，材料模块用它做简历结构化抽取，面试模块用它出题和生成报告。
 * 如果留在岗位模块里（原来是 {@code app.job.ai}），其它模块就得反过来依赖岗位模块，
 * 依赖方向就错了。所以它和 {@link AiClient} 一起提到了 common。
 *
 * <p>密钥优先级：请求头 &gt; 这里的 {@code api-key} &gt; 降级为离线替身。
 * {@code api-key} 留空表示"必须由前端携带用户自己的 token"。
 */
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** OpenAI 兼容端点。DeepSeek / 通义 / 豆包 / 自建网关都按这个协议接。 */
    private String baseUrl = "https://api.deepseek.com/v1";

    private String model = "deepseek-chat";

    /**
     * 服务端兜底密钥。留空表示必须由前端携带用户自己的 token。
     *
     * <p>不要提交真实密钥，用环境变量注入：{@code AI_API_KEY}。
     */
    private String apiKey = "";

    /**
     * 允许浏览器自定义模型地址使用的 HTTPS origin 列表。
     *
     * <p>只填 origin（协议 + 主机 [+ 端口]），不带路径。用户带自己的密钥时，
     * 请求里给的 Base URL 必须命中其中之一，否则拒绝。
     *
     * <p>不加这一层的话，任何非空用户密钥加一个自定义地址都能驱动后端去请求
     * 环回或内网地址，等于给了 SSRF 入口。部署者要接自建网关就把自己的
     * 可信 HTTPS 网关登记进来；服务端自身配置不受此限制。
     *
     * <p>这段配置和 {@code AiClientResolver#validateBrowserEndpoint} 一起，
     * 构成了「用户密钥不能把后端变成内网探测器」的防线。
     */
    private List<String> allowedBrowserOrigins = new ArrayList<>(List.of(
            "https://api.deepseek.com",
            "https://dashscope.aliyuncs.com",
            "https://ark.cn-beijing.volces.com"));

    /** 单次调用超时（秒）。材料抽取比匹配解释耗时，可以调大。 */
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

    public List<String> getAllowedBrowserOrigins() {
        return allowedBrowserOrigins;
    }

    public void setAllowedBrowserOrigins(List<String> allowedBrowserOrigins) {
        // 防御性拷贝：配置对象可能被多次设置，直接持有外部列表容易被意外改掉
        this.allowedBrowserOrigins = allowedBrowserOrigins == null
                ? new ArrayList<>()
                : new ArrayList<>(allowedBrowserOrigins);
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
