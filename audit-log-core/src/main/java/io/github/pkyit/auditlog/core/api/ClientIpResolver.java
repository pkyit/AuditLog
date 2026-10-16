package io.github.pkyit.auditlog.core.api;

import java.util.Optional;

/**
 * 客户端真实 IP 解析 SPI。
 *
 * <p>默认实现不信任任何代理头：{@code X-Forwarded-For} 等头可由客户端任意伪造，
 * 只有在部署方显式配置了可信代理网段后才应开启代理头解析，否则落库 IP 不可作为取证依据。
 */
@FunctionalInterface
public interface ClientIpResolver {

	/**
	 * 解析客户端 IP。
	 *
	 * @param context 解析上下文
	 * @return IP；空表示无法确定
	 */
	Optional<String> resolve(ResolutionContext context);

	/** 顺序，值越小越先执行。 */
	default int getOrder() {
		return 100;
	}

	/**
	 * 解析上下文：实现只能读取这里暴露的请求元数据。
	 */
	interface ResolutionContext {

		/** 直连地址（TCP 层对端，不可伪造）。 */
		String getRemoteAddress();

		/** 请求头访问器（大小写不敏感）；伪造风险由 {@link #isTrustedProxy()} 界定。 */
		String getHeader(String name);

		/** 直连对端是否可信：返回 {@code false} 时不得采信任何代理头。 */
		boolean isTrustedProxy();
	}
}
