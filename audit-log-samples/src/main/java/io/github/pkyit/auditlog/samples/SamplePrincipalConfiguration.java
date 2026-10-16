package io.github.pkyit.auditlog.samples;

import io.github.pkyit.auditlog.core.api.PrincipalInfo;
import io.github.pkyit.auditlog.core.api.PrincipalResolver;
import io.github.pkyit.auditlog.core.model.PrincipalType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * 示例：通过 SPI 接管主体解析（验收项 A9）。
 *
 * <p>真实系统中的主体通常来自应用自有认证框架，而不是 Spring Security 或通用请求头。
 * 扩展方式是提供一个 {@code PrincipalResolver} Bean：框架默认解析链会优先采用它，
 * 不需要修改框架代码。
 *
 * <p>这里使用应用私有的请求头名（{@code X-Sample-User}），模拟认证层已解析好身份
 * 并放入请求作用域的形态。
 *
 * <p>线程模型约束：本解析器从请求头读取身份，而不是从 {@code ThreadLocal} 读取。
 * Servlet 请求由容器工作线程处理，与业务代码所在线程可能不同，{@code ThreadLocal}
 * 中的身份在线程切换后会不可见，不能作为主体来源。
 */
@Configuration(proxyBeanMethods = false)
public class SamplePrincipalConfiguration {

	/** 模拟应用自有认证层注入身份的请求头。 */
	public static final String SAMPLE_USER_HEADER = "X-Sample-User";

	/**
	 * 注册示例主体解析器。
	 *
	 * <p>order 为 5，优先于框架内置的认证上下文解析器；请求头缺失或为空白时不解析主体。
	 *
	 * @return 从 {@value #SAMPLE_USER_HEADER} 请求头解析主体的解析器
	 */
	@Bean
	public PrincipalResolver samplePrincipalResolver() {
		return new PrincipalResolver() {
			@Override
			public Optional<PrincipalInfo> resolve(ResolutionContext context) {
				String userId = context.getHeader(SAMPLE_USER_HEADER);
				if (userId == null || userId.isBlank()) {
					return Optional.empty();
				}
				return Optional.of(new PrincipalInfo(userId.trim(), userId.trim(),
					PrincipalType.AUTHENTICATED, "sample-internal", null));
			}

			@Override
			public int getOrder() {
				// 高于框架内置的认证上下文解析器，确保本示例的自定义来源优先参与
				return 5;
			}
		};
	}
}
