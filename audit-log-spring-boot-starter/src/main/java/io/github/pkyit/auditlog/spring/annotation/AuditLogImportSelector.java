package io.github.pkyit.auditlog.spring.annotation;

import io.github.pkyit.auditlog.spring.autoconfigure.AuditLogAnnotationProperties;
import io.github.pkyit.auditlog.spring.autoconfigure.AuditLogAutoConfiguration;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@link EnableAuditLog} 的导入选择器。
 *
 * <p>只做两件事：注册自动配置，并把注解上声明的扫描范围作为 {@link AuditLogAnnotationProperties}
 * 记录下来供诊断使用。
 *
 * <p>方法级元数据只能取自 Spring 真实的 HandlerMapping（见 {@code AuditMethodRegistry}），
 * 不得改用类路径扫描：扫描会通过 {@code ClassUtils.forName} 提前加载包内所有类，触发静态初始化，
 * 并在可选依赖缺失时直接导致应用启动失败。
 */
public class AuditLogImportSelector implements ImportSelector {

	@Override
	public String[] selectImports(AnnotationMetadata importingClassMetadata) {
		AnnotationAttributes attributes = AnnotationAttributes.fromMap(
			importingClassMetadata.getAnnotationAttributes(EnableAuditLog.class.getName(), true));
		if (attributes == null) {
			return new String[]{AuditLogAutoConfiguration.class.getName()};
		}
		if (!attributes.getBoolean("enable")) {
			// enable=false 时不注册任何审计组件，避免留下半个配置：
			// 部分组件注册后会在构造期查找不存在的 bean，使禁用功能反而启动失败。
			return new String[0];
		}
		Set<String> packages = new LinkedHashSet<>();
		packages.addAll(Arrays.asList(attributes.getStringArray("scanBasePackages")));
		packages.addAll(Arrays.asList(attributes.getStringArray("basePackages")));
		packages.removeIf(value -> value == null || value.isBlank());
		if (packages.isEmpty()) {
			packages.add(org.springframework.util.ClassUtils
				.getPackageName(importingClassMetadata.getClassName()));
		}
		// 注解属性的实际注册由 AuditLogAnnotationRegistrar 完成：属性必须注册为 Bean 定义，
		// 在此处直接 new 出的实例不会被其他组件注入，注解属性会静默失效。
		return new String[]{AuditLogAutoConfiguration.class.getName()};
	}
}
