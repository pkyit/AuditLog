package io.github.pkyit.auditlog.spring.reactive;

import io.github.pkyit.auditlog.spring.collect.AuditCaptureService;
import io.github.pkyit.auditlog.spring.collect.AuditRecordContext;
import io.github.pkyit.auditlog.spring.properties.AuditLogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 审计边界采集过滤器（Reactive / WebFlux 实现）。
 *
 * <p>职责：请求进入时建立唯一审计上下文，请求结束时发布唯一一条记录，
 * 认证失败、404、参数绑定失败与业务异常均须落入记录（发布幂等由
 * {@link AuditReactiveSupport#PUBLISHED_ATTRIBUTE} 保证）。
 *
 * <p>反应式下有三条硬约束：
 * <ol>
 *   <li>上下文不能放在 {@code ThreadLocal}：同一请求可能在多个线程/事件循环间流转
 *       （{@code delayElements}、{@code subscribeOn}、{@code publishOn} 都会切换线程），
 *       因此存放在 {@code ServerWebExchange.getAttributes()} 上；</li>
 *   <li>请求体为 {@code Flux<DataBuffer>}，只能在不消费它的前提下旁路留存，
 *       通过 {@link ServerHttpRequestDecorator} 替换 exchange 的请求（见 {@link AuditBodyTee}）；</li>
 *   <li>收尾用 {@code doFinally}：正常完成、异常、取消三种终止都会触发，
 *       覆盖正常返回、业务异常与客户端断开。</li>
 * </ol>
 *
 * <p>顺序取自配置项，默认 {@code -150}，早于 Spring Security 的反应式过滤链（{@code -100}），
 * 使 401/403 请求同样经过审计。
 */
public class AuditLogWebFilter implements WebFilter, Ordered {

	private static final Logger log = LoggerFactory.getLogger(AuditLogWebFilter.class);

	private final AuditCaptureService captureService;
	private final AuditLogProperties properties;

	public AuditLogWebFilter(AuditCaptureService captureService, AuditLogProperties properties) {
		this.captureService = captureService;
		this.properties = properties;
		log.info("[audit-log] 审计边界过滤器已创建（Reactive/WebFilter，order={}，include={}，exclude={}）",
			properties.getCollector().getBoundary().getOrder(),
			properties.getCollector().getBoundary().getIncludePatterns(),
			properties.getCollector().getBoundary().getExcludePatterns());
	}

	@Override
	public int getOrder() {
		return properties.getCollector().getBoundary().getOrder();
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		if (!shouldAudit(exchange)) {
			return chain.filter(exchange);
		}

		// 1) 旁路缓存请求体：替换 exchange 的请求，业务读到的仍是完整数据
		ServerWebExchange effectiveExchange = teeRequestBody(exchange);

		// 2) 建立上下文并挂到 exchange 属性（反应式下无 ThreadLocal 可用）
		AuditRecordContext context = null;
		try {
			context = captureService.begin(new ReactiveAuditRequestView(effectiveExchange));
			effectiveExchange.getAttributes().put(AuditReactiveSupport.CONTEXT_ATTRIBUTE, context);
		}
		catch (Throwable error) {
			// 采集起点失败不得影响业务请求
			captureService.reportFailure("begin", "审计上下文初始化失败（reactive）", error);
		}

		// 3) 注册提交时收尾：必须早于框架提交响应，否则整类请求会漏采
		registerCommitFinalizer(effectiveExchange, context);

		final AuditRecordContext finalContext = context;
		return chain.filter(effectiveExchange)
			// 暂存异常，供收尾阶段判定 outcome/error
			.doOnError(error -> rememberError(effectiveExchange, error))
			// 兜底：客户端取消时响应不再提交，提交回调不会触发，
			// 必须在此收尾，否则该记录丢失。
			.doFinally(signal -> {
				if (signal == reactor.core.publisher.SignalType.CANCEL) {
					finish(finalContext, effectiveExchange, null);
				}
			});
	}

	/**
	 * 注册响应提交时的收尾回调，须在过滤器进入阶段调用。
	 *
	 * <p>收尾为何发生在提交时刻：{@code WebFilter} 返回的 Mono 在响应写出之前即完成，
	 * 那一时刻状态码通常尚未确定；提交是状态码落定的时刻，也覆盖了异常被 Spring
	 * 错误处理映射为状态码的情形。
	 *
	 * <p>为何不能等到出错后再注册：成功路径的提交发生在框架的 {@code handleResult} 中，
	 * 到 {@code doOnError} 时该提交点已经过去，这些请求将失去收尾机会。
	 *
	 * <p>代价：提交时刻约等于响应头写出时刻，因此流式响应记录到的是首字节耗时，
	 * 而非整个流结束的耗时。此处以状态码正确性优先于流式耗时精度：状态码错误会污染
	 * 下游统计，耗时偏小只影响精度。若要求流式耗时精确，需包装响应体数据流并在其完成时
	 * 收尾，代价是额外复杂度，且受"响应提交后状态码不可再改"的约束。
	 */
	private void registerCommitFinalizer(ServerWebExchange exchange, AuditRecordContext context) {
		if (context == null) {
			return;
		}
		exchange.getResponse().beforeCommit(() -> {
			Object code = exchange.getResponse().getStatusCode() == null
				? null : exchange.getResponse().getStatusCode().value();
			if (code != null) {
				exchange.getAttributes().put(AuditReactiveSupport.COMMITTED_STATUS_ATTRIBUTE, code);
			}
			finish(context, exchange, null);
			return Mono.empty();
		});
	}

	/** 暂存异常：收尾回调中取不到 {@code doFinally} 的终止信号。 */
	private void rememberError(ServerWebExchange exchange, Throwable error) {
		exchange.getAttributes().put(AuditReactiveSupport.ERROR_ATTRIBUTE, error);
	}

	/**
	 * 在响应提交时把最终状态码写入 exchange 属性。
	 *
	 * <p>提交是状态码落定的时刻，也覆盖了异常被 Spring 错误处理映射为状态码的情形：
	 * 该路径下 handler Mono 以 error 完成，状态码在错误渲染时才写入响应。
	 */
	private void captureCommittedStatus(ServerWebExchange exchange) {
		org.springframework.http.server.reactive.ServerHttpResponse response = exchange.getResponse();
		response.beforeCommit(() -> {
			org.springframework.http.HttpStatusCode code = response.getStatusCode();
			if (code != null) {
				exchange.getAttributes().put(AuditReactiveSupport.COMMITTED_STATUS_ATTRIBUTE, code.value());
			}
			return Mono.empty();
		});
	}

	/** 发布记录（幂等：exchange 属性标记保证只发布一次）。 */
	private void finish(AuditRecordContext context, ServerWebExchange exchange, Throwable error) {
		if (context == null) {
			return;
		}
		if (exchange.getAttributes().putIfAbsent(AuditReactiveSupport.PUBLISHED_ATTRIBUTE, Boolean.TRUE) != null) {
			return;
		}
		// 优先使用显式传入的异常：beforeCommit 回调中取不到 doFinally 的终止信号
		Throwable effectiveError = error;
		if (effectiveError == null) {
			Object remembered = exchange.getAttributes().get(AuditReactiveSupport.ERROR_ATTRIBUTE);
			if (remembered instanceof Throwable throwable) {
				effectiveError = throwable;
			}
		}
		try {
			// 语义填充不在此处进行。BEST_MATCHING_HANDLER 属性在本位置读不到：
			// DispatcherHandler 把该属性写入 mutate 派生出的另一个 exchange，
			// 而 Spring 6.2 的 ServerWebExchange.Builder 没有 attributes(...) 方法，
			// 属性表无法共享进来。
			//
			// 语义填充的唯一入口是 ReactiveSemanticChannelProcessor（包装 HandlerAdapter）：
			// 那里能同时拿到即将执行的 HandlerMethod 与实际执行的 exchange，
			// 因此按请求参数解析资源 ID 也能完成。填充必须只保留一处：
			// 多处填充会因执行顺序不确定而先到者胜，而先执行的一方拿不到请求，
			// 资源 ID 将始终解析不出来。
			captureService.finish(context, new ReactiveAuditRequestView(exchange),
				new ReactiveAuditResponseView(exchange), effectiveError);
		}
		catch (Throwable finishError) {
			captureService.reportFailure("finish", "审计记录发布失败（reactive）", finishError);
		}
	}

	/**
	 * 请求有体且缓存上限为正时，把 exchange 的请求替换为带旁路拷贝的版本。
	 *
	 * <p>业务读到的字节不受影响：装饰器只在上游数据流上附加观察式拷贝，元素与顺序原样透传。
	 *
	 * @return 派生出的 exchange；调用方后续必须使用返回值，原始 exchange 仍持有未包装的请求
	 */
	private ServerWebExchange teeRequestBody(ServerWebExchange exchange) {
		int maxCache = properties.getCollector().getBoundary().getMaxCachedBodyBytes();
		if (maxCache <= 0) {
			return exchange;
		}
		ServerHttpRequest request = exchange.getRequest();
		if (!hasBody(request)) {
			return exchange;
		}
		Charset charset = resolveCharset(request);
		AuditBodyTee.BodySink sink = new AuditBodyTee.BodySink();
		// 先放入 sink 占位：内容随数据流过填充，收尾阶段再读取
		exchange.getAttributes().put(AuditReactiveSupport.BODY_SINK_ATTRIBUTE, sink);

		ServerHttpRequestDecorator decorator = new ServerHttpRequestDecorator(request) {
			@Override
			public Flux<DataBuffer> getBody() {
				return AuditBodyTee.tee(super.getBody(), maxCache, sink);
			}
		};
		return exchange.mutate().request(decorator).build();
	}

	private boolean hasBody(ServerHttpRequest request) {
		String method = request.getMethod() == null ? "" : request.getMethod().name();
		if (!("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
				|| "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method))) {
			return false;
		}
		long length = request.getHeaders().getContentLength();
		return length != 0L;
	}

	private Charset resolveCharset(ServerHttpRequest request) {
		org.springframework.http.MediaType contentType = request.getHeaders().getContentType();
		Charset charset = contentType == null ? null : contentType.getCharset();
		return charset == null ? StandardCharsets.UTF_8 : charset;
	}

	/**
	 * 判定是否采集该请求，include/exclude 语义与 Servlet 侧一致。
	 *
	 * <p>匹配必须使用 {@code AntPathMatcher}：排除模式是 Ant 表达式，前缀匹配会与 Servlet 侧
	 * 产生行为差异，同一 URI 在两种环境下可能得到相反结论。
	 *
	 * @return exclude 命中返回 {@code false}；include 为空时返回 {@code recordUnmatched} 的配置值；
	 *         否则要求 include 命中
	 */
	private boolean shouldAudit(ServerWebExchange exchange) {
		String uri = exchange.getRequest().getURI().getRawPath();
		org.springframework.util.AntPathMatcher matcher =
			new org.springframework.util.AntPathMatcher();
		AuditLogProperties.Boundary boundary = properties.getCollector().getBoundary();
		java.util.List<String> excludes = boundary.getExcludePatterns();
		if (excludes != null) {
			for (String pattern : excludes) {
				if (pattern != null && !pattern.isBlank() && matcher.match(pattern.trim(), uri)) {
					return false;
				}
			}
		}
		java.util.List<String> includes = boundary.getIncludePatterns();
		if (includes == null || includes.isEmpty()) {
			return boundary.isRecordUnmatched();
		}
		for (String pattern : includes) {
			if (pattern != null && !pattern.isBlank() && matcher.match(pattern.trim(), uri)) {
				return true;
			}
		}
		return false;
	}
}
