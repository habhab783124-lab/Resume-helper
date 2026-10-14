package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ApiResponse;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

// Multipart parsing fails before a controller is selected, so this advice must be global.
@RestControllerAdvice
@Order(-2)
public class UploadErrorHandler {
    private final MultipartProperties limits;

    public UploadErrorHandler(MultipartProperties limits) {
        this.limits = limits;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(ApiResponse.fail("上传内容超过大小限制（单文件上限 "
                + readableSize(limits.getMaxFileSize()) + "，请求总量上限 "
                + readableSize(limits.getMaxRequestSize()) + "）"));
    }

    private static String readableSize(DataSize size) {
        long bytes = size.toBytes();
        return bytes % (1024 * 1024) == 0 ? size.toMegabytes() + "MB" : bytes + "B";
    }
}
