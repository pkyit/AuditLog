package io.github.pkyit.auditlog.spring.annotation;

import io.github.pkyit.auditlog.core.model.ActionType;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级审计注解。
 *
 * <p>标注在方法或类型上，被拦截的方法调用将生成审计记录。{@code value}、{@code name}、
 * {@code logHeaders}、{@code logRequestParams}、{@code logResponseParams}、
 * {@code sensitiveParams} 必须保持既有语义：存量配置依赖这些属性名，语义变更会导致
 * 已上线的审计策略静默失效。
 *
 * <p>自由文本操作名无法可靠分类，因此新增 {@link #actionType()}、{@link #resourceType()}、
 * {@link #resourceId()}、{@link #resourceIdParam()} 四个属性，以结构化字段表达「什么动作、
 * 作用于什么资源」，作为分类检索与告警规则的依据。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface AuditLog {

	/**
	 * 操作名称，与 {@link #name()} 互为别名。
	 */
	@AliasFor("name")
	String value() default "";

	/**
	 * 操作名称，与 {@link #value()} 互为别名。
	 */
	@AliasFor("value")
	String name() default "";

	/**
	 * 动作类型。
	 *
	 * <p>用于按类别聚合与匹配告警规则，未声明时为 {@link ActionType#UNKNOWN}。
	 */
	ActionType actionType() default ActionType.UNKNOWN;

	/**
	 * 资源类型，例如 {@code user}、{@code order}、{@code report}。
	 */
	String resourceType() default "";

	/**
	 * 资源标识，支持 SpEL，可用变量：{@code #id}、{@code #paramName}、{@code #args[n]}。
	 *
	 * <p>例如 {@code resourceId = "#userId"}。
	 */
	String resourceId() default "";

	/**
	 * 从请求参数中提取资源 ID 的参数名。
	 *
	 * <p>优先于 {@link #resourceId()}：本属性非空且能从请求中取到非空值时直接采用，
	 * 仅在取不到值时才回退到 {@link #resourceId()} 的 SpEL 表达式。
	 */
	String resourceIdParam() default "";

	/**
	 * 是否记录请求头，默认记录。
	 */
	boolean logHeaders() default true;

	/**
	 * 是否记录请求参数，默认记录。
	 */
	boolean logRequestParams() default true;

	/**
	 * 是否记录响应参数，默认不记录：响应体可能包含敏感字段。
	 */
	boolean logResponseParams() default false;

	/**
	 * 额外排除的敏感字段与请求头名称。
	 */
	String[] sensitiveParams() default {};

	/**
	 * 标记为敏感操作。
	 *
	 * <p>置为 {@code true} 时审计记录写入 {@code sensitive=true} 标签，便于单独告警与统计。
	 */
	boolean sensitive() default false;

	/**
	 * 是否跳过审计。
	 *
	 * <p>用于在类型级开启审计后单独排除某个方法。
	 */
	boolean ignore() default false;

	/**
	 * 自定义标签，写入审计记录的 tags，形如 {@code {"biz":"report"}}。
	 */
	String[] tags() default {};
}
