package io.github.pkyit.auditlog.spring.reactive;

import io.github.pkyit.auditlog.spring.collect.AuditCaptureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerAdapter;

/**
 * 注册 Reactive 语义通道：把 WebFlux 的注解适配器包一层。
 *
 * <p>装配方式受两点约束：
 * <ul>
 *   <li>包装必须早于 HandlerAdapter bean 被使用与解析：{@code DispatcherHandler} 按类型
 *       一次性获取适配器列表，晚于该时点返回的包装实例不会被采用，
 *       语义填充会静默失效且无异常可查；</li>
 *   <li>自行构造 {@code DispatcherHandler} 并注入包装版不可行：该类不提供适配器列表的 setter，
 *       公开构造器只接受 {@code ApplicationContext}。</li>
 * </ul>
 * 因此采用后处理器尽早包装：包装成功时输出 info 日志（beanName 与适配器类型），
 * 可据此确认语义通道是否生效。
 */
@Configuration(proxyBeanMethods = false)
public class ReactiveSemanticChannelConfiguration {

	private static final Logger log = LoggerFactory.getLogger(ReactiveSemanticChannelConfiguration.class);

	/**
	 * 尽早注册注解适配器的包装后处理器。
	 *
	 * <p>方法声明为 {@code static}：{@code BeanPostProcessor} 必须在本配置类实例化之前完成注册，
	 * 否则已创建的适配器 bean 不会被包装。顺序取 {@code HIGHEST_PRECEDENCE + 50}：
	 * 早于其他后处理器，同时让出最前面的位置给框架自身的必要处理。
	 */
	@Bean
	public static AdapterWrappingPostProcessor auditAdapterPostProcessor(AuditCaptureService captureService) {
		return new AdapterWrappingPostProcessor(captureService);
	}

	/** 包装 WebFlux 注解适配器的后处理器。 */
	static final class AdapterWrappingPostProcessor implements BeanPostProcessor, Ordered {

		private final AuditCaptureService captureService;

		AdapterWrappingPostProcessor(AuditCaptureService captureService) {
			this.captureService = captureService;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
			if (bean instanceof HandlerAdapter adapter && !ReactiveSemanticChannelProcessor.isWrapped(adapter)) {
				HandlerAdapter wrapped = ReactiveSemanticChannelProcessor.wrap(captureService, adapter);
				if (wrapped != adapter) {
					log.info("[audit-log] Reactive 语义通道已包装适配器 beanName={} type={}",
						beanName, adapter.getClass().getSimpleName());
					return wrapped;
				}
			}
			return bean;
		}

		/** 实际生效的排序取自实例方法，须与 {@code auditAdapterPostProcessor} 声明的顺序一致。 */
		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE + 50;
		}
	}
}
