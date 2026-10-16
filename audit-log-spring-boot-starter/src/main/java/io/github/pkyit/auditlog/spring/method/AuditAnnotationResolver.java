package io.github.pkyit.auditlog.spring.method;

import io.github.pkyit.auditlog.spring.annotation.AuditLog;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

/**
 * {@code @AuditLog} 注解解析工具，Web 环境无关。
 *
 * <p>不得依赖 Servlet 类型：Servlet 侧在启动扫描时用它构造元数据，Reactive 侧在请求期直接解析
 * （见 {@link ResolvingAuditMethodRegistry}），两种环境共用同一份解析逻辑，
 * 保证注解合并规则（方法级覆盖类级）在两侧完全一致。
 */
public final class AuditAnnotationResolver {

	private AuditAnnotationResolver() {
	}

	/**
	 * 解析方法上生效的 {@code @AuditLog}：先查方法级，未命中再回退查声明类。
	 *
	 * <p>注解声明了 {@code @Target({METHOD, TYPE})} + {@code @Inherited}，
	 * 但 {@code AnnotatedElementUtils.findMergedAnnotation(Method, ...)} 不会向上查找声明类上的注解，
	 * 因此必须显式回退；否则类级用法会静默失效。
	 *
	 * <p>合并语义：方法级覆盖类级。
	 *
	 * @param handlerMethod Spring MVC 已匹配的 Handler 方法，允许为 {@code null}
	 * @return {@code handlerMethod} 为 {@code null} 或方法级、类级均无注解时返回 {@code null}
	 */
	public static AuditLog resolve(HandlerMethod handlerMethod) {
		if (handlerMethod == null) {
			return null;
		}
		AuditLog onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), AuditLog.class);
		if (onMethod != null) {
			return onMethod;
		}
		Class<?> beanType = handlerMethod.getBeanType();
		if (beanType == null) {
			return null;
		}
		return AnnotatedElementUtils.findMergedAnnotation(beanType, AuditLog.class);
	}

	/**
	 * 稳定 key：类名#方法名(参数类型)。
	 *
	 * <p>类名取方法声明类的全限定名；两种环境共用同一格式，保证诊断信息可对齐。
	 *
	 * @param handlerMethod Spring MVC 已匹配的 Handler 方法
	 * @return 不含 {@code null} 的 key 字符串
	 */
	public static String keyOf(HandlerMethod handlerMethod) {
		Method method = handlerMethod.getMethod();
		StringBuilder builder = new StringBuilder(96);
		builder.append(method.getDeclaringClass().getName()).append('#').append(method.getName()).append('(');
		Class<?>[] parameters = method.getParameterTypes();
		for (int index = 0; index < parameters.length; index++) {
			if (index > 0) {
				builder.append(',');
			}
			builder.append(parameters[index].getSimpleName());
		}
		return builder.append(')').toString();
	}
}
