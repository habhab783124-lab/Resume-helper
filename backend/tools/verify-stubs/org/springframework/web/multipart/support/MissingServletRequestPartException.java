package org.springframework.web.multipart.support;
public class MissingServletRequestPartException extends RuntimeException {
    public MissingServletRequestPartException(String requestPartName) { super(requestPartName); }
    public String getRequestPartName() { return ""; }
}