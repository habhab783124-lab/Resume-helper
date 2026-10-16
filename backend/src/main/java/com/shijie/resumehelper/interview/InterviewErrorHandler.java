package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

// 必须显式列出控制器：advice 的兜底异常处理器不会作用到未声明的控制器上，
// 语音接口漏掉的话，InterviewException 会掉进全局兜底变成 500。
@RestControllerAdvice(assignableTypes = {InterviewController.class, InterviewSpeechController.class})
@Order(-1)
public class InterviewErrorHandler {
    @ExceptionHandler(InterviewException.class)
    public ResponseEntity<ApiResponse<Object>> interview(InterviewException e) {
        // 失败 data 为 InterviewFailure（可机器读取的 code/retryable/retryAfterMs）；
        // 无 failure 的纯人类可读错误仍保持 data 为 null。
        return ResponseEntity.status(e.status())
                .body(new ApiResponse<>(false, e.getMessage(), e.failure()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(e.getMessage()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> malformed(Exception e) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("请求格式不正确，请检查文件及必填字段"));
    }
}
