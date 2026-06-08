package com.hmdp.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({java.lang.annotation.ElementType.METHOD})
@Retention(RUNTIME)

public @interface PhoneMarkAnnotation {
    //    比如让使用者指定要脱敏的字段名
    String value() default "phone";
}
