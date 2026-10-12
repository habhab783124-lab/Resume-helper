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

    /**
     * 跨域配置。
     *
     * <p>前端是 Vite 开发服务器（{@code http://localhost:5173} / {@code http://127.0.0.1:5173}），
     * 后端是 {@code http://localhost:8081}，端口不同必然跨域。
     *
     * <p>两种解法，任选其一：
     * <ol>
     *   <li>用这里的 CORS 配置（默认已开启）；</li>
     *   <li>前端 {@code vite.config.js} 里加代理，把 {@code /api} 转发到后端，见 backend/README.md。</li>
     * </ol>
     *
     * <p>允许的请求头包含用户自带 token 用到的三个自定义头，否则浏览器预检会失败。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*")
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
