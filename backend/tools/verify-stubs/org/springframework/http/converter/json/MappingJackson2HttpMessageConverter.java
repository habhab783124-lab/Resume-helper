package org.springframework.http.converter.json;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.converter.HttpMessageConverter;
import java.nio.charset.Charset;
public class MappingJackson2HttpMessageConverter implements HttpMessageConverter<Object> {
    public MappingJackson2HttpMessageConverter() { }
    public MappingJackson2HttpMessageConverter(ObjectMapper mapper) { }
    public void setDefaultCharset(Charset charset) { }
}