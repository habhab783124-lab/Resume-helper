package org.springframework.web;
public class HttpRequestMethodNotSupportedException extends RuntimeException {
    public HttpRequestMethodNotSupportedException(String message) { super(message); }
    public String getMethod() { return ""; }
}