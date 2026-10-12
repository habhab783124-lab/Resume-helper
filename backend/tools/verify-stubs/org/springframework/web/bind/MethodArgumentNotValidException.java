package org.springframework.web.bind;
public class MethodArgumentNotValidException extends RuntimeException {
    public BindingResult getBindingResult() { return new BindingResult(); }
    public static class BindingResult {
        public java.util.List<FieldError> getFieldErrors() { return java.util.List.of(); }
    }
    public static class FieldError {
        public String getField() { return ""; }
        public String getDefaultMessage() { return ""; }
    }
}