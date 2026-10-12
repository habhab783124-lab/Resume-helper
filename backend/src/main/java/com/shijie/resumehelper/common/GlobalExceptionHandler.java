package com.shijie.resumehelper.common;

import com.shijie.resumehelper.job.ai.AiClient;
import com.shijie.resumehelper.job.source.JobSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理：把异常翻译成前端能看懂的提示，同时避免把堆栈或密钥泄露出去。
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

    /** 兜底。日志里只留异常类型和消息，不回传给前端。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("未处理的服务端异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("服务端开小差了，请稍后重试"));
    }
}
