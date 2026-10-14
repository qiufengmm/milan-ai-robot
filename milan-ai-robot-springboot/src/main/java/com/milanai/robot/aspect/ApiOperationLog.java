package com.milanai.robot.aspect;

import java.lang.annotation.*;

/**
 * @Version: v1.0.0
 * @Description: 日志切面注解
 **/
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
@Documented
public @interface ApiOperationLog {

    /**
     * API 功能描述
     *
     * @return
     */
    String description() default "";

}

