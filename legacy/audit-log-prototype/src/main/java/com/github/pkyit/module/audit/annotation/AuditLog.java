package com.github.pkyit.module.audit.annotation;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

/**
 * 审计日志注解，用于记录用户的行为，作用在Controller类的请求方法上
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

	/**
	 * 操作名称
	 */
	@AliasFor("name")
	String value() default "";

	/**
	 * 操作名称
	 */
	@AliasFor("value")
	String name() default "";

	/**
	 * 是否记录请求头
	 */
	boolean logHeaders() default true;

	/**
	 * 是否记录请求参数
	 */
	boolean logRequestParams() default true;

	/**
	 * 是否记录响应参数
	 */
	boolean logResponseParams() default true;

	/**
	 * 排除敏感信息，防止审计时记录到用户敏感数据
	 */
	String[] sensitiveParams() default {"authorization", "password", "secret", "token"};
}
