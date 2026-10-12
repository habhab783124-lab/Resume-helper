package com.shijie.resumehelper.common;

import com.shijie.resumehelper.job.ai.AiClient;
import com.shijie.resumehelper.job.source.JobSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：把异常翻译成前端能看懂的提示，同时避免把堆栈或密钥泄露出去。
 *
 * <p><b>踩过的坑</b>：兜底的 {@code @ExceptionHandler(Exception.class)} 很容易"太贪婪"——
 * Spring 自己的框架异常（找不到资源、请求体不是 JSON、方法不支持）也会被它吃掉，
 * 全部变成 500。调用方明明是"路径写错"或"JSON 传错"，却只看到"服务端开小差了"。
 * 所以框架异常必须逐个显式处理，返回正确的状态码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数校验失败（@Valid）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("；"));
        return ResponseEntity.badRequest().body(ApiResponse.fail("请求参数有误：" + message));
    }

    /** 业务参数不合法，例如岗位描述为空。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(e.getMessage()));
    }

    /** 岗位来源不可用。 */
    @ExceptionHandler(JobSource.JobSourceException.class)
    public ResponseEntity<ApiResponse<Void>> handleJobSource(JobSource.JobSourceException e) {
        log.warn("岗位来源异常 [{}]：{}", e.sourceName(), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.fail("岗位来源「" + e.sourceName() + "」暂时不可用：" + e.getMessage()));
    }

    /**
     * 大模型调用失败。
     *
     * <p>正常情况下 {@code JobMatchService} 和 {@code JobSearchAgent} 会先把 {@code AiException}
     * 接住并降级为规则逻辑，不会走到这里。这个处理器是兜底：
     * 万一异常逃到了控制器层，也要告诉用户「是 token / 网络的问题」，
     * 而不是笼统的「服务端开小差了」。
     *
     * <p>注意：异常消息里只包含状态码和模型侧的错误描述，
     * 不含 API Key（{@link com.shijie.resumehelper.job.ai.AiCredentials#maskedKey()} 是唯一允许打日志的形式）。
     */
    @ExceptionHandler(AiClient.AiException.class)
    public ResponseEntity<ApiResponse<Void>> handleAi(AiClient.AiException e) {
        log.warn("大模型调用失败（可重试={}）：{}", e.retryable(), e.getMessage());
        HttpStatus status = e.retryable() ? HttpStatus.BAD_GATEWAY : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ApiResponse.fail(e.getMessage()));
    }

    /**
     * 静态资源 / 路由不存在，应该返回 404。
     *
     * <p><b>为什么必须单独处理它</b>：Spring 6.1 起，找不到静态资源会抛
     * {@link NoResourceFoundException}。如果只有下面那个 {@code Exception} 兜底，
     * 它会被当成"服务端未处理异常"变成 <b>500</b>——
     * 实测就是这样：{@code GET /api/写错的路径} 返回 500「服务端开小差了」，
     * 排查时完全看不出是"路径写错了"。
     *
     * <p>这个坑在单进程演示模式下尤其容易踩：后端同时托管前端静态文件，
     * 写错任何路径都会走到资源处理器。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail("接口或资源不存在：" + e.getMessage()));
    }

    /**
     * 请求体不是合法 JSON，或字段类型对不上，应该返回 400。
     *
     * <p>同样是"被兜底吃掉会变成 500"的典型：本来一眼能看出是调用方传错了参数，
     * 变成 500 之后就得翻日志才能定位。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail("请求体不是合法 JSON，或字段类型不对。请检查 Content-Type 是否为 application/json，以及中文字符是否按 UTF-8 编码"));
    }

    /** HTTP 方法不支持（例如对只接受 GET 的接口发 POST），应该返回 405。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.fail("该接口不支持 " + e.getMethod() + " 方法"));
    }

    /** 兜底。日志里只留异常类型和消息，不回传给前端。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("未处理的服务端异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("服务端开小差了，请稍后重试"));
    }
}
