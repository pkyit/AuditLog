package com.github.oldbee.auditlog.config;

import com.github.oldbee.auditlog.annotation.EnableAuditLog;
import com.github.oldbee.auditlog.annotation.Log;
import com.github.oldbee.auditlog.model.AuditLogConfig;
import com.github.oldbee.auditlog.model.MethodLog;
import com.github.oldbee.auditlog.web.AuditLogWebMvcRegistrations;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 审计日志核心配置类
 */
public class AuditLogConfiguration implements ImportBeanDefinitionRegistrar, EnvironmentAware, BeanFactoryAware {

    private BeanFactory beanFactory;
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(EnableAuditLog.class.getName(), true);
        String enableStr = this.environment.resolvePlaceholders(attrs.get("enable").toString());
        ConfigurableBeanFactory cbf = (ConfigurableBeanFactory) this.beanFactory;
        ConversionService cs = cbf.getConversionService();
        Boolean enable = cs.convert(enableStr, Boolean.class);
        if (!enable) {
            return;
        }
        String logHeaderStr = this.environment.resolvePlaceholders(attrs.get("logHeaders").toString());
        Boolean logHeaders = cs.convert(logHeaderStr, Boolean.class);
        String logRequestStr = this.environment.resolvePlaceholders(attrs.get("logRequest").toString());
        Boolean logRequest = cs.convert(logRequestStr, Boolean.class);
        String logResponseStr = this.environment.resolvePlaceholders(attrs.get("logResponse").toString());
        Boolean logResponse = cs.convert(logResponseStr, Boolean.class);
        String[] sensitiveHeaders = (String[]) attrs.get("sensitiveHeaders");
        Map<Method, MethodLog> methodLogs = scanLog(registry, getPackagesToScan(metadata), cs);
        AuditLogConfig auditLogConfig = new AuditLogConfig(
                enable, logHeaders, logRequest, logResponse, Set.of(sensitiveHeaders), methodLogs);

        // 1.将解析的日志记录配置注册为bean
        AbstractBeanDefinition bd = BeanDefinitionBuilder.genericBeanDefinition(
                        AuditLogConfig.class, () -> auditLogConfig)
                .getBeanDefinition();
        registry.registerBeanDefinition(AuditLogConfig.class.getSimpleName(), bd);

        // 2.注册自定义HandlerAdapter
        AbstractBeanDefinition beanDefinition = BeanDefinitionBuilder
                .genericBeanDefinition(AuditLogWebMvcRegistrations.class)
                .getBeanDefinition();
        registry.registerBeanDefinition(AuditLogWebMvcRegistrations.class.getSimpleName(), beanDefinition);
    }

    private Map<Method, MethodLog> scanLog(BeanDefinitionRegistry registry, Set<String> packagesToScan, ConversionService cs) {
        Map<Method, MethodLog> methodLogs = new HashMap<>();
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(registry, false) {
            @Override
            protected boolean isCandidateComponent(MetadataReader metadataReader) throws IOException {
                boolean ret = super.isCandidateComponent(metadataReader);
                if (ret) {
                    // 检查类是否有 @Log 注解接口
                    checkLogMethod(metadataReader);
                }
                return ret;
            }

            private void checkLogMethod(MetadataReader metadataReader) throws IOException {
                try {
                    Class<?> clazz = ClassUtils.forName(metadataReader.getClassMetadata().getClassName(),
                            AuditLogConfiguration.class.getClassLoader());
                    ReflectionUtils.doWithMethods(clazz, new ReflectionUtils.MethodCallback() {
                        @Override
                        public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
                            Log log = method.getAnnotation(Log.class);
                            if (log != null) {
                                String enable = environment.resolvePlaceholders(log.enable());
                                String logHeader = environment.resolvePlaceholders(log.logHeaders());
                                String logRequest = environment.resolvePlaceholders(log.logRequest());
                                String logResponse = environment.resolvePlaceholders(log.logResponse());
                                String[] sensitiveHeaders = log.sensitiveHeaders();
                                MethodLog methodLog = new MethodLog(
                                        cs.convert(enable, Boolean.class),
                                        cs.convert(logHeader, Boolean.class),
                                        cs.convert(logRequest, Boolean.class),
                                        cs.convert(logResponse, Boolean.class),
                                        Set.of(sensitiveHeaders));
                                methodLogs.put(method, methodLog);
                            }
                        }
                    });
                } catch (Exception e) {
                    throw new IOException("Failed to load class", e);
                }
            }
        };
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(Controller.class));
        for (String pkg : packagesToScan) {
            scanner.scan(pkg);
        }
        return methodLogs;
    }

    private Set<String> getPackagesToScan(AnnotationMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(EnableAuditLog.class.getName());
        Set<String> packagesToScan = new LinkedHashSet<>();
        for (String basePackage : (String[]) attributes.get("basePackages")) {
            String[] tokenized = StringUtils.tokenizeToStringArray(
                    this.environment.resolvePlaceholders(basePackage),
                    ConfigurableApplicationContext.CONFIG_LOCATION_DELIMITERS);
            Collections.addAll(packagesToScan, tokenized);
        }
        if (packagesToScan.isEmpty()) {
            String packageName = ClassUtils.getPackageName(metadata.getClassName());
            return Collections.singleton(packageName);
        }
        return packagesToScan;
    }

}