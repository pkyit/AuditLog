package io.github.pkyit.auditlog.spring.autoconfigure;

import io.github.pkyit.auditlog.spring.annotation.EnableAuditLog;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 把 {@link EnableAuditLog} 上声明的属性注册为可注入的 Bean。
 *
 * <p>注解上的 {@code scanBasePackages} / {@code basePackages} / {@code methodCollector} /
 * {@code defaultActionType} 必须在此进入容器，否则使用者在注解上表达的意图既不生效也不告警，
 * 形成注解层面的静默失效。
 *
 * <p>选用 {@code ImportBeanDefinitionRegistrar} 而非 {@code ImportSelector}，
 * 是因为只有前者能直接注册 Bean 定义：属性对象需要作为单例进入容器，
 * 后续的启动校验与诊断端点才能按类型注入并消费。
 */
public class AuditLogAnnotationRegistrar implements ImportBeanDefinitionRegistrar {

	/** 注册到容器中的 Bean 名。 */
	public static final String BEAN_NAME = "auditLogAnnotationProperties";

	/**
	 * 注册注解属性 Bean。
	 *
	 * <p>前置条件：{@code importingClassMetadata} 带有 {@link EnableAuditLog}。
	 * 缺少注解或 {@code enable=false} 时不注册任何 Bean，因此容器中可能不存在该类型的实例，
	 * 注入方必须按可选依赖处理。空白的包名会被丢弃；{@code scanBasePackages} 与
	 * {@code basePackages} 的取值合并去重后作为扫描范围。
	 *
	 * <p>后置条件：注册名为 {@link #BEAN_NAME} 的单例 Bean 定义。
	 */
	@Override
	public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
		AnnotationAttributes attributes = AnnotationAttributes.fromMap(
			importingClassMetadata.getAnnotationAttributes(EnableAuditLog.class.getName(), true));
		if (attributes == null || !attributes.getBoolean("enable")) {
			return;
		}
		Set<String> packages = new LinkedHashSet<>();
		packages.addAll(Arrays.asList(attributes.getStringArray("scanBasePackages")));
		packages.addAll(Arrays.asList(attributes.getStringArray("basePackages")));
		packages.removeIf(value -> value == null || value.isBlank());

		String defaultActionType = String.valueOf(attributes.getEnum("defaultActionType"));
		AuditLogAnnotationProperties properties = new AuditLogAnnotationProperties(
			packages.stream().toList(),
			attributes.getBoolean("methodCollector"),
			defaultActionType);

		RootBeanDefinition definition = new RootBeanDefinition(AuditLogAnnotationProperties.class);
		// 直接注册已构造好的实例：它是不可变值对象，无需走完整的实例化流程
		definition.setInstanceSupplier(() -> properties);
		registry.registerBeanDefinition(BEAN_NAME, definition);
	}
}
