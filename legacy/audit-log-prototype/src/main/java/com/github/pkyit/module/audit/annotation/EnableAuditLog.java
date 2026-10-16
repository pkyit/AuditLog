package com.github.pkyit.module.audit.annotation;

import com.github.pkyit.module.audit.config.AuditLogConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

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
	 * 是否开启此功能，默认开启
	 */
	boolean enable() default true;

	/**
	 * 需要扫描的包路径
	 */
	String[] scanBasePackages() default {};

}
