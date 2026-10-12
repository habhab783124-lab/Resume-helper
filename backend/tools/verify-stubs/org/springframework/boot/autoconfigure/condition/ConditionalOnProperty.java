package org.springframework.boot.autoconfigure.condition;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ConditionalOnProperty {
    String[] value() default {};
    String prefix() default "";
    String[] name() default {};
    String havingValue() default "";
    boolean matchIfMissing() default false;
}