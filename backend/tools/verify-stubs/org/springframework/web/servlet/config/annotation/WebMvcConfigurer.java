package org.springframework.web.servlet.config.annotation;
import org.springframework.http.converter.HttpMessageConverter;
import java.util.List;
public interface WebMvcConfigurer {
    default void addCorsMappings(CorsRegistry registry) { }
    default void extendMessageConverters(List<HttpMessageConverter<?>> converters) { }
}