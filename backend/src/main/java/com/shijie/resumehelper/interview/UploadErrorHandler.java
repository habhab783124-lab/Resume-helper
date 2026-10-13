package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

// Multipart parsing fails before a controller is selected, so this advice must be global.
@RestControllerAdvice
@Order(-2)
public class UploadErrorHandler {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(ApiResponse.fail("上传文件不能超过 5MB"));
    }
}
