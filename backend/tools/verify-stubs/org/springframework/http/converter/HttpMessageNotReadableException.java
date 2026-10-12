package org.springframework.http.converter;
public class HttpMessageNotReadableException extends RuntimeException {
    public HttpMessageNotReadableException(String message) { super(message); }
    public HttpMessageNotReadableException(String message, Throwable cause) { super(message, cause); }
}