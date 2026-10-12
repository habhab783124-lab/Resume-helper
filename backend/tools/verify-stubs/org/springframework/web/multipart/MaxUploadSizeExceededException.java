package org.springframework.web.multipart;
public class MaxUploadSizeExceededException extends RuntimeException {
    public MaxUploadSizeExceededException(String message) { super(message); }
    public MaxUploadSizeExceededException(long maxUploadSize) { super("max=" + maxUploadSize); }
    public long getMaxUploadSize() { return 0L; }
}