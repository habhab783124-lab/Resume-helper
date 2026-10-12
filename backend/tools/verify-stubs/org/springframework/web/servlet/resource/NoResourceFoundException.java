package org.springframework.web.servlet.resource;
public class NoResourceFoundException extends RuntimeException {
    public NoResourceFoundException(String message) { super(message); }
    public String getResourcePath() { return ""; }
    public String getHttpMethod() { return ""; }
}