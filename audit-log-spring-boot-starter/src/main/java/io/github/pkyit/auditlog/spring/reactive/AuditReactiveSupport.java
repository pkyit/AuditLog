package io.github.pkyit.auditlog.spring.reactive;

/**
 * Reactive 适配的共享常量与工具。
 *
 * <p>请求级状态在反应式下无法使用 {@code ThreadLocal}，全部存放在
 * {@code ServerWebExchange.getAttributes()} 上，因此属性 key 必须集中定义：
 * key 分散于各类时，任何一处不一致都会让读取方静默取不到值，
 * 表现为审计字段成片为空且无异常可查。
 */
public final class AuditReactiveSupport {

	private AuditReactiveSupport() {
	}

	/** 审计上下文（{@code AuditRecordContext}）在 exchange 属性里的 key。 */
	public static final String CONTEXT_ATTRIBUTE = AuditReactiveSupport.class.getName() + ".context";

	/**
	 * 请求体累积容器（{@code AuditBodyTee.BodySink}）在 exchange 属性里的 key。
	 *
	 * <p>属性值必须是容器而非文本快照：请求体异步流过，过滤器建立上下文时内容尚未到齐，
	 * 此时存放的字符串快照到收尾阶段仍为空。容器随数据流过实时填充，收尾时再取文本。
	 */
	public static final String BODY_SINK_ATTRIBUTE = AuditReactiveSupport.class.getName() + ".bodySink";

	/** 发布幂等标记：同一请求的审计记录只允许发布一次。 */
	public static final String PUBLISHED_ATTRIBUTE = AuditReactiveSupport.class.getName() + ".published";

	/**
	 * 响应提交时刻捕获的最终状态码（{@code Integer}）在 exchange 属性里的 key。
	 *
	 * <p>{@code WebFilter} 返回的 Mono 在响应体写出之前即完成，
	 * 收尾时 {@code ServerHttpResponse.getStatusCode()} 通常仍为空。
	 * 最终状态码只能在提交回调中读取，故在此暂存，收尾阶段优先使用。
	 */
	public static final String COMMITTED_STATUS_ATTRIBUTE =
		AuditReactiveSupport.class.getName() + ".committedStatus";

	/**
	 * 请求处理过程中捕获的异常（{@code Throwable}）在 exchange 属性里的 key。
	 *
	 * <p>收尾发生在 {@code beforeCommit} 回调中，那里取不到 {@code doFinally} 的终止信号，
	 * 异常必须提前暂存；否则失败的请求会被记为 SUCCESS。
	 */
	public static final String ERROR_ATTRIBUTE = AuditReactiveSupport.class.getName() + ".error";

	/**
	 * WebFlux 匹配到的 {@code HandlerMethod} 在 exchange 属性里的 key。
	 *
	 * <p>此处使用字符串字面量而不引用
	 * {@code HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE}：后者来自 {@code spring-webflux}，
	 * 而本类在 Servlet 与 Reactive 两种宿主下都会被加载，直接引用会强制 Servlet 宿主
	 * 也必须具备 webflux 的类。字面量的取值即 Spring 公开约定的属性名，与此处常量一致。
	 */
	public static final String BEST_MATCHING_HANDLER = "org.springframework.web.reactive.HandlerMapping.bestMatchingHandler";

	/** 请求体缓存默认上限（字节），在未配置或配置为非正值时生效。 */
	public static final int DEFAULT_MAX_BODY_BYTES = 64 * 1024;
}
