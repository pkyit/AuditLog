package com.github.pkyit.module.audit.config;

import com.github.pkyit.module.audit.annotation.AuditLog;
import com.github.pkyit.module.audit.annotation.EnableAuditLog;
import com.github.pkyit.module.audit.model.bo.MethodAuditLogBO;
import com.github.pkyit.module.audit.webmvc.AuditLogWebMvcRegistrations;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 审计日志核心配置类
 */
public class AuditLogConfiguration implements ImportBeanDefinitionRegistrar {
	private static final Log logger = LogFactory.getLog(AuditLogConfiguration.class);

	/**
	 * 注册审计日志核心bean
	 */
	@Override
	public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry,
										BeanNameGenerator importBeanNameGenerator) {

		// 获取EnableAuditLog注解属性
		Map<String, Object> annotationAttributes = importingClassMetadata.getAnnotationAttributes(EnableAuditLog.class.getName(), true);
		// 如果关闭了日志审计功能，则直接返回
		if (annotationAttributes == null || !(boolean) annotationAttributes.get("enable")) {
			logger.debug("未启用审计日志功能");
			return;
		}

		// 如果启用了审计日志功能
		// 获取包的扫描路径
		String[] basePackages = (String[]) annotationAttributes.get("scanBasePackages");
		if (basePackages == null || basePackages.length == 0) {
			// 如果没有指定包路径，则使用默认的包路径，使用当前注解位置所在的包及其子包
			basePackages = new String[]{
				ClassUtils.getPackageName(importingClassMetadata.getClassName())
			};
			logger.warn("审计日志未指定包路径，将使用当前注解位置所在的包及其子包" + basePackages[0]);
		}

		// 否则使用@EnableAuditLog注解的scanBasePackages指定的包路径
		logger.debug("审计日志包路径：" + Arrays.toString(basePackages));


		// 1、扫描包路径下的类，找出加了@AuditLog注解的controller方法，存入一个不可变且全局唯一的去重集合中
		Map<Method, MethodAuditLogBO> auditLogMethods = scanAuditLogAnnotation(basePackages);

		// 将auditLogMethods注册为bean
		// 2、将全局的auditLogMethods集合注册为bean
		BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.rootBeanDefinition(LinkedHashMap.class);
		beanDefinitionBuilder.addConstructorArgValue(auditLogMethods);
		registry.registerBeanDefinition("auditLogMethods", beanDefinitionBuilder.getBeanDefinition());


		// 2、注册审计日志核心bean
		AbstractBeanDefinition beanDefinition = BeanDefinitionBuilder
			.genericBeanDefinition(AuditLogWebMvcRegistrations.class)
			.getBeanDefinition();
		registry.registerBeanDefinition("auditLogWebMvcRegistrations", beanDefinition);
	}

	private Map<Method, MethodAuditLogBO> scanAuditLogAnnotation(String[] basePackages) {
		Map<Method, MethodAuditLogBO> resultMap = new LinkedHashMap<>();

		// 先把basePackages转换为set集合去重，防止包路径重复扫描
		Set<String> basePackagesSet = new HashSet<>(Arrays.asList(basePackages));
		for (String basePackage : basePackagesSet) {
			// 扫描并获取basePackage包及其子包下所有同时加了@AuditLog注解和@RequestMapping的方法，把方法信息保存到resultMap中
			try {
				// 将包名转换为资源路径
				String packageSearchPath = "classpath*:" + basePackage.replace('.', '/') + "/**/*.class";

				ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();
				MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory(resourcePatternResolver);

				Resource[] resources = resourcePatternResolver.getResources(packageSearchPath);
				for (Resource resource : resources) {
					if (resource.isReadable()) {
						MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(resource);

						// 加载类
						String className = metadataReader.getClassMetadata().getClassName();
						Class<?> clazz = ClassUtils.forName(className, Thread.currentThread().getContextClassLoader());

						// 获取类中所有方法并检查是否有@AuditLog注解和@RequestMapping注解
						Method[] classMethods = clazz.getDeclaredMethods();
						for (Method method : classMethods) {
							if (method.isAnnotationPresent(AuditLog.class) && AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
								// 获取@AuditLog注解信息
								AuditLog auditLog = method.getAnnotation(AuditLog.class);

								// 创建MethodAuditLogBO对象
								MethodAuditLogBO methodAuditLogBO = new MethodAuditLogBO(
									auditLog.name().isEmpty() ? auditLog.value() : auditLog.name(),
									auditLog.logHeaders(),
									auditLog.logRequestParams(),
									auditLog.logResponseParams(),
									new HashSet<>(Arrays.asList(auditLog.sensitiveParams()))
								);

								// 将方法和对应的BO对象放入Map中
								resultMap.put(method, methodAuditLogBO);

								logger.debug("发现同时带@AuditLog和@RequestMapping注解的方法: " + clazz.getSimpleName() + "." + method.getName());
							}
						}
					}
				}
			} catch (IOException | ClassNotFoundException e) {
				logger.error("扫描包路径 " + basePackage + " 下的注解方法时发生错误", e);
			}
		}
		return Collections.unmodifiableMap(resultMap);
	}

}
