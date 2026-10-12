package com.shijie.resumehelper.job.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Web 层配置：跨域 + JSON 响应编码。
 */
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final JobModuleProperties properties;

    public WebMvcConfiguration(JobModuleProperties properties) {
        this.properties = properties;
    }

    /**
     * 跨域配置。
     *
     * <p>本地开发时前端是 vite（{@code localhost:5173}），后端是 {@code localhost:8080}，
     * 如果前端配了代理就不会跨域；没配代理也能靠这里放行。
     *
     * <p><b>前端部署到公网后必须改配置</b>：Vercel 之类的域名不在默认白名单里，
     * 浏览器会拦掉请求。把域名加到 {@code application.yml} 的
     * {@code app.job.cors.allowed-origin-patterns} 即可，不需要改代码。
     *
     * <p>允许的请求头包含用户自带 token 用到的三个自定义头，否则浏览器预检会失败。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> patterns = properties.getCors().getAllowedOriginPatterns();
        if (patterns.isEmpty()) {
            // 配置被清空时退回只放行本机，避免为了图方便写成 "*" 又把凭据一起放开
            patterns = List.of("http://localhost:*", "http://127.0.0.1:*");
        }
        registry.addMapping("/api/**")
                .allowedOriginPatterns(patterns.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-AI-Model")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 给 JSON 响应显式加上 {@code charset=UTF-8}。
     *
     * <p><b>这不是在修 bug</b>：JSON 规范（RFC 8259）规定交换用的 JSON 必须是 UTF-8，
     * Spring Boot 默认发的 {@code Content-Type: application/json} 完全正确，
     * 浏览器、axios、fetch 解析中文都没问题。实测服务端返回的字节流确实是合法 UTF-8。
     *
     * <p>加这一句是为了照顾**宽容度差的客户端**：
     * Windows PowerShell 5.1 的 {@code Invoke-RestMethod} 在响应头没有 charset 时会按
     * ISO-8859-1 解码，把「岗位库」显示成「å²ä½åº」；
     * 有些老版本 HTTP 工具也有同样毛病。队友手测接口时看到乱码会白白浪费时间排查。
     *
     * <p>声明 charset 对规范兼容的客户端没有任何副作用。
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter jacksonConverter) {
                jacksonConverter.setDefaultCharset(StandardCharsets.UTF_8);
            }
        }
    }
}
