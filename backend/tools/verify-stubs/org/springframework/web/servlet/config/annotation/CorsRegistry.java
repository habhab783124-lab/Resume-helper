package org.springframework.web.servlet.config.annotation;
public class CorsRegistry {
    public CorsRegistration addMapping(String pathPattern) { return new CorsRegistration(); }
    public static class CorsRegistration {
        public CorsRegistration allowedOriginPatterns(String... patterns) { return this; }
        public CorsRegistration allowedMethods(String... methods) { return this; }
        public CorsRegistration allowedHeaders(String... headers) { return this; }
        public CorsRegistration exposedHeaders(String... headers) { return this; }
        public CorsRegistration allowCredentials(boolean allow) { return this; }
        public CorsRegistration maxAge(long age) { return this; }
    }
}