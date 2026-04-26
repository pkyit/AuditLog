package com.github.oldbee.auditlog.annotation;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

/**
 * 用于精细化控制具体Controller接口的日志行为
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Log {

    /**
     * 是否开启此功能;支持${...}表达式
     */
    @AliasFor("enable")
    String value() default "true";

    /**
     * 是否开启此功能;支持${...}表达式
     */
    @AliasFor("value")
    String enable() default "true";

    /**
     * 记录请求headers信息
     */
    String logHeaders() default "true";

    /**
     * 记录请求参数
     */
    String logRequest() default "true";

    /**
     * 记录响应数据
     */
    String logResponse() default "true";

    /**
     * 保护敏感数据：防止敏感 Header 被记录
     */
    String[] sensitiveHeaders() default {"Authorization"};
}