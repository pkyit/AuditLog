package io.github.pkyit.auditlog.spring.method;

import io.github.pkyit.auditlog.core.model.ActionType;
import io.github.pkyit.auditlog.spring.annotation.AuditLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单个 Handler 方法的审计元数据。
 *
 * <p>实例不可变：集合字段在构造时被复制为不可修改视图，因此可安全地在多线程间共享。
 *
 * @param key               稳定标识：{@code 类名#方法名(参数签名)}
 * @param controllerType    控制器类简单名（写入日志便于阅读）
 * @param controllerClassName 控制器类全限定名（用于按参数名解析资源 ID）
 * @param methodName        方法名
 * @param argumentNames     方法参数名（编译期开启 {@code -parameters} 时可用）
 * @param operation         操作名称（注解 {@code name}/{@code value}，为空时回退为 {@code 类名.方法名}）
 * @param actionType        动作类型
 * @param resourceType      资源类型
 * @param resourceId        资源 ID（支持 {@code #参数名} / {@code #args[n]} 表达式）
 * @param resourceIdParam   资源 ID 所在请求参数名
 * @param logHeaders        是否记录请求头
 * @param logRequestParams  是否记录请求参数
 * @param logResponseParams 是否记录响应参数
 * @param sensitiveParams   额外敏感字段名（与全局脱敏规则合并生效，不受是否记录请求参数影响）
 * @param sensitive         是否敏感操作
 * @param tags              自定义标签；由 {@link #of} 从注解解析，无 {@code =} 的标签取值固定为 {@code "true"}
 */
public record AuditMethodMetadata(
	String key,
	String controllerType,
	String controllerClassName,
	String methodName,
	List<String> argumentNames,
	String operation,
	ActionType actionType,
	String resourceType,
	String resourceId,
	String resourceIdParam,
	boolean logHeaders,
	boolean logRequestParams,
	boolean logResponseParams,
	Set<String> sensitiveParams,
	boolean sensitive,
	Map<String, String> tags) {

	public AuditMethodMetadata {
		argumentNames = argumentNames == null ? Collections.emptyList() : List.copyOf(argumentNames);
		sensitiveParams = sensitiveParams == null
			? Collections.emptySet()
			: Collections.unmodifiableSet(new LinkedHashSet<>(sensitiveParams));
		tags = tags == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
	}

	/**
	 * 从 Handler 方法与注解构造元数据。
	 *
	 * <p>编译期未开启 {@code -parameters} 时参数名为空串，按参数名解析资源 ID 将失败，
	 * {@code resourceIdParam} 形式不受影响。
	 *
	 * @param key           稳定标识，由 {@link ServletMethodRegistry#keyOf} 生成
	 * @param handlerMethod Spring MVC 已匹配的 Handler 方法，其 Bean 类型取自控制器实例的类
	 * @param annotation    该方法上生效的注解，须已合并类级与忽略标记
	 * @return 字段已归一化的元数据实例
	 */
	public static AuditMethodMetadata of(String key, org.springframework.web.method.HandlerMethod handlerMethod,
			AuditLog annotation) {
		String declaredName = !annotation.name().isBlank() ? annotation.name() : annotation.value();
		Class<?> beanType = handlerMethod.getBeanType();
		String controllerType = beanType.getSimpleName();
		java.lang.reflect.Method method = handlerMethod.getMethod();
		String methodName = method.getName();
		String operation = declaredName.isBlank() ? controllerType + "." + methodName : declaredName;
		List<String> argumentNames = new ArrayList<>(method.getParameterCount());
		for (java.lang.reflect.Parameter parameter : method.getParameters()) {
			argumentNames.add(parameter.isNamePresent() ? parameter.getName() : "");
		}
		Map<String, String> tags = new LinkedHashMap<>();
		for (String tag : annotation.tags()) {
			if (tag == null || tag.isBlank()) {
				continue;
			}
			int index = tag.indexOf('=');
			if (index > 0) {
				tags.put(tag.substring(0, index).trim(), tag.substring(index + 1).trim());
			}
			else {
				tags.put(tag.trim(), "true");
			}
		}
		if (annotation.sensitive()) {
			tags.put("sensitive", "true");
		}
		return new AuditMethodMetadata(
			key,
			controllerType,
			beanType.getName(),
			methodName,
			argumentNames,
			operation,
			annotation.actionType(),
			annotation.resourceType(),
			annotation.resourceId(),
			annotation.resourceIdParam(),
			annotation.logHeaders(),
			annotation.logRequestParams(),
			annotation.logResponseParams(),
			new LinkedHashSet<>(java.util.Arrays.asList(annotation.sensitiveParams())),
			annotation.sensitive(),
			tags);
	}

	/**
	 * 按参数名返回实参值。
	 *
	 * @param name 参数名，与 {@link #argumentNames} 中的元素比较
	 * @param args 方法实参，仅前 {@code min(argumentNames.size(), args.length)} 个参与匹配
	 * @return 参数名不存在、参数名未编译进字节码或 {@code args} 为 {@code null} 时返回 {@code null}
	 */
	public Object argumentValue(String name, Object[] args) {
		if (name == null || args == null) {
			return null;
		}
		for (int index = 0; index < argumentNames.size() && index < args.length; index++) {
			if (name.equals(argumentNames.get(index))) {
				return args[index];
			}
		}
		return null;
	}

	/**
	 * 便捷构造：生成仅含 key、操作名与动作类型的元数据，其余字段取固定占位值。
	 *
	 * <p>仅供测试使用。
	 *
	 * @param key        稳定标识，格式与生产路径一致
	 * @param operation  操作名称
	 * @param actionType 动作类型
	 * @return 控制类与参数信息均为占位值的元数据实例
	 */
	public static AuditMethodMetadata simple(String key, String operation, ActionType actionType) {
		return new AuditMethodMetadata(key, "TestController", "TestController", "test", Collections.emptyList(),
			operation, actionType, "", "", "", true, true, false, Collections.emptySet(), false,
			Collections.emptyMap());
	}
}
