package org.springframework.boot.autoconfigure;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SpringBootApplication { Class<?>[] exclude() default {}; String[] scanBasePackages() default {}; }