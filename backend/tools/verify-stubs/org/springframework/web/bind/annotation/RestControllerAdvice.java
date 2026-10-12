package org.springframework.web.bind.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RestControllerAdvice { String[] value() default {}; String[] basePackages() default {}; }