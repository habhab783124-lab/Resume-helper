package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.util.TextKit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 按请求构造语音客户端。
 *
 * <p>和 {@code AiClientResolver} 是同一个理由：用户自带密钥意味着
 * <b>每个请求的密钥都可能不同</b>。把带密钥的客户端注册成单例，
 * 就会出现「A 用户的请求用了 B 用户的 key」这类串号问题。
 * 这里每次调用都新建一个轻量客户端，内部复用的是静态 HttpClient，
 * 请求结束随同凭据一起回收。
 *
 * <p>密钥来源优先级：请求头 &gt; 服务端配置。
 *
 * <p>语音端点是代码常量、不接受浏览器传入任意 URL，所以这里不需要
 * {@code allowed-browser-origins} 那类目的地址校验——那条防线只在大模型
 * 端点需要，因为模型地址由请求方决定，而语音地址写死在服务端。
 */
@Component
public class SpeechClientResolver {

    /** 用户自己的火山豆包语音 API Key。 */
    public static final String HEADER_API_KEY = "X-Speech-Api-Key";

    private final SpeechProperties properties;
    private final ObjectMapper mapper;

    public SpeechClientResolver(SpeechProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    /** 本次调用实际使用的密钥：请求头优先，留空则退回服务端配置。 */
    public String apiKey(HttpServletRequest request) {
        String header = request == null ? "" : TextKit.trim(request.getHeader(HEADER_API_KEY));
        return header.isEmpty() ? TextKit.trim(properties.getApiKey()) : header;
    }

    public SpeechClient resolve(HttpServletRequest request) {
        return new VolcengineSpeechClient(properties, mapper, apiKey(request));
    }
}
