package org.springframework.http;
public enum HttpStatus {
    OK(200), BAD_REQUEST(400), NOT_FOUND(404), BAD_GATEWAY(502), INTERNAL_SERVER_ERROR(500);
    private final int value;
    HttpStatus(int value) { this.value = value; }
    public int value() { return value; }
}