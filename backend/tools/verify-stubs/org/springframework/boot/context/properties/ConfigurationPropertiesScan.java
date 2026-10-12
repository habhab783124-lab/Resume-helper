package org.springframework.boot.context.properties;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConfigurationPropertiesScan { String[] value() default {}; String[] basePackages() default {}; }