package com.github.oldbee.auditlog.annotation;

import com.github.oldbee.auditlog.config.AuditLogConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Controller;

import java.lang.annotation.*;

/**
 * 启用审计日志功能的注解
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(AuditLogConfiguration.class)
@Documented
public @interface EnableAuditLog {
    
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
     * 扫描的包
     */
    String[] basePackages() default {};

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
     * 保护敏感数据：防止 Authorization等敏感 Header 被记录
     */
    String[] sensitiveHeaders() default {"Authorization"};
}