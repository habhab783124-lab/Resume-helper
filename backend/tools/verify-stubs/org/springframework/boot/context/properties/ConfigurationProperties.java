package org.springframework.boot.context.properties;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConfigurationProperties { String prefix() default ""; String value() default ""; }