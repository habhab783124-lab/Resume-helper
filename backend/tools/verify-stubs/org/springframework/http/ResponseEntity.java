package org.springframework.http;
public class ResponseEntity<T> {
    private final HttpStatus status;
    private final T body;
    private ResponseEntity(HttpStatus status, T body) { this.status = status; this.body = body; }
    public T getBody() { return body; }
    public HttpStatus getStatusCode() { return status; }
    public static BodyBuilder badRequest() { return new BodyBuilder(HttpStatus.BAD_REQUEST); }
    public static BodyBuilder status(HttpStatus status) { return new BodyBuilder(status); }
    public static final class BodyBuilder {
        private final HttpStatus status;
        private BodyBuilder(HttpStatus status) { this.status = status; }
        public <T> ResponseEntity<T> body(T body) { return new ResponseEntity<>(status, body); }
    }
}