package org.springframework.boot.context.properties;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EnableConfigurationProperties { Class<?>[] value() default {}; }