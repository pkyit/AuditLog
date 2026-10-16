package io.github.pkyit.auditlog.spring.autoconfigure;

import java.util.List;

/**
 * {@link io.github.pkyit.auditlog.spring.annotation.EnableAuditLog} 的原始配置载体。
 *
 * <p>保存注解上声明的扫描范围与开关，供启动校验与诊断读取：注解声明与实际采集范围
 * 必须可互相印证，否则注解层面的配置错误只会表现为静默失效。
 *
 * <p>本类不可变，构造完成后可被多线程安全共享。
 */
public class AuditLogAnnotationProperties {

	private final List<String> basePackages;
	private final boolean methodCollector;
	private final String defaultActionType;

	/**
	 * 创建注解配置快照。
	 *
	 * @param basePackages 注解声明的扫描包名；为 {@code null} 时按空列表处理，
	 *                     非 {@code null} 时复制为独立列表
	 * @param methodCollector 是否启用方法级采集通道
	 * @param defaultActionType 注解声明的默认动作类型，允许为 {@code null}
	 * @throws NullPointerException 若 {@code basePackages} 含 {@code null} 元素
	 */
	public AuditLogAnnotationProperties(List<String> basePackages, boolean methodCollector,
			String defaultActionType) {
		this.basePackages = basePackages == null ? List.of() : List.copyOf(basePackages);
		this.methodCollector = methodCollector;
		this.defaultActionType = defaultActionType;
	}

	/**
	 * 注解声明的扫描包名。
	 *
	 * @return 不可变列表；未声明时为空列表，不含 {@code null} 元素
	 */
	public List<String> getBasePackages() {
		return basePackages;
	}

	/**
	 * 注解声明的 {@code methodCollector} 取值。
	 *
	 * @return 是否启用方法级（语义）采集通道
	 */
	public boolean isMethodCollector() {
		return methodCollector;
	}

	/**
	 * 注解声明的默认动作类型，方法未标注动作类型时使用。
	 *
	 * @return 动作类型名；未声明时为 {@code null}
	 */
	public String getDefaultActionType() {
		return defaultActionType;
	}

	@Override
	public String toString() {
		return "AuditLogAnnotationProperties{basePackages=" + basePackages
			+ ", methodCollector=" + methodCollector
			+ ", defaultActionType='" + defaultActionType + "'}";
	}
}
