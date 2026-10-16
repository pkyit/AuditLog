package io.github.pkyit.auditlog.spring.annotation;

import io.github.pkyit.auditlog.core.model.ActionType;
import io.github.pkyit.auditlog.core.model.PrincipalType;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 启用审计日志。
 *
 * <p>引入 starter 依赖后自动配置即生效，通常不需要本注解。它用于两类场景：
 * 把方法级采集限制在指定包内以缩小扫描范围，以及显式声明启用意图。
 *
 * <p>{@code enable} 与 {@code scanBasePackages} 必须保持既有语义：存量配置依赖这两个属性名，
 * 语义变更会导致已上线的审计策略静默失效。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Import({AuditLogImportSelector.class,
	io.github.pkyit.auditlog.spring.autoconfigure.AuditLogAnnotationRegistrar.class})
public @interface EnableAuditLog {

	/**
	 * 是否开启审计。置为 {@code false} 时不注册任何审计组件。
	 */
	boolean enable() default true;

	/**
	 * 需要扫描的包路径，与 {@link #basePackages()} 互为别名。
	 *
	 * <p>为空表示以注解所在类为根包。
	 */
	@AliasFor("basePackages")
	String[] scanBasePackages() default {};

	/**
	 * {@link #scanBasePackages()} 的别名。
	 */
	@AliasFor("scanBasePackages")
	String[] basePackages() default {};

	/**
	 * 需要排除的包与类。
	 *
	 * <p>当前仅作为注解属性保存，方法级采集的排除判定未读取该属性。
	 */
	Class<?>[] exclude() default {};

	/**
	 * 默认主体来源。
	 *
	 * <p>当前仅作为注解属性保存，主体解析未读取该属性；主体来源由
	 * {@code audit.log.identity.*} 决定。
	 */
	PrincipalType defaultPrincipalType() default PrincipalType.AUTHENTICATED;

	/**
	 * 是否允许方法级采集。
	 *
	 * <p>等价于配置项 {@code audit.log.collector.method.enabled}。
	 */
	boolean methodCollector() default true;

	/**
	 * 默认动作类型，方法未标注 {@link AuditLog#actionType()} 时使用。
	 */
	ActionType defaultActionType() default ActionType.UNKNOWN;
}
