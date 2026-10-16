package io.github.pkyit.auditlog.core.api;

import java.util.Optional;

/**
 * 主体解析器 SPI。
 *
 * <p>实现按 {@link #getOrder()} 升序串行尝试，第一个返回非空结果的实现胜出；
 * 全部返回空则主体为匿名。框架内置：
 * <ol>
 *   <li>{@code SecurityContextPrincipalResolver} —— 从 Spring Security 认证上下文读取（可信）；</li>
 *   <li>{@code HeaderPrincipalResolver} —— 从信任请求头读取（默认关闭，开启后标注为不可信/受信头）；</li>
 *   <li>{@code AnonymousPrincipalResolver} —— 兜底。</li>
 * </ol>
 */
@FunctionalInterface
public interface PrincipalResolver {

	/**
	 * 解析当前请求的主体。
	 *
	 * @param context 解析上下文（可访问请求元数据，不依赖 Servlet 类型）
	 * @return 主体；{@link Optional#empty()} 表示交给下一个解析器
	 */
	Optional<PrincipalInfo> resolve(ResolutionContext context);

	/** 顺序，值越小越先执行。 */
	default int getOrder() {
		return 100;
	}

	/**
	 * 解析上下文：实现只能读取这里暴露的请求元数据，不依赖 Servlet 类型。
	 */
	interface ResolutionContext {

		/** 读取请求头（大小写不敏感）；不存在时返回 {@code null}。 */
		String getHeader(String name);

		/** 请求 URI（不含查询串）。 */
		String getRequestUri();

		/** HTTP 方法（大写，如 {@code GET}）。 */
		String getHttpMethod();

		/** 读取框架在请求生命周期内挂载的属性；不存在时返回 {@code null}。 */
		String getAttribute(String name);
	}
}
