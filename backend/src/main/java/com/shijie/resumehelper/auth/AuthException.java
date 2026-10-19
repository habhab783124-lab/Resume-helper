package com.shijie.resumehelper.auth;

import org.springframework.http.HttpStatus;

/**
 * 认证相关的业务异常：邮箱重复、密码错误、令牌过期等。
 *
 * <p>单独抽出来是为了让 {@code GlobalExceptionHandler} 能返回准确的
 * HTTP 状态码（401 / 409），而不是把所有业务错误都当成 400。
 */
public class AuthException extends RuntimeException {

    private final HttpStatus status;

    public AuthException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
