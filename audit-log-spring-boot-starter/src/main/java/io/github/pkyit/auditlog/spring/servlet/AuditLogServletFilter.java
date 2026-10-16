package io.github.pkyit.auditlog.spring.servlet;

import io.github.pkyit.auditlog.spring.collect.AuditCaptureService;
import io.github.pkyit.auditlog.spring.collect.AuditRecordContext;
import io.github.pkyit.auditlog.spring.collect.AuditRequestContextHolder;
import io.github.pkyit.auditlog.spring.properties.AuditLogProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 审计边界采集过滤器。
 *
 * <p>采集点必须落在过滤器链这一层：认证失败、401/403、限流熔断、参数绑定失败、404
 * 等安全事件都在进入 Handler 之前就已结束，挂在 Handler 调用上的采集点无法覆盖。
 *
 * <p>职责：
 * <ol>
 *   <li>请求进入时建立唯一审计上下文（来源、主体、链路、URI、方法）；</li>
 *   <li>请求结束时（成功、异常或被下游过滤器拒绝）补真实状态码与异常并发布记录，
 *       每条请求只发布一次，语义通道只向同一上下文补字段；</li>
 *   <li>采集逻辑整体包在 try-catch 内，审计自身的异常不得影响业务。</li>
 * </ol>
 *
 * <p>默认顺序早于 Spring Security（{@code order = -100}），因此 403 响应也会经过
 * {@code afterCompletion} 并被记录。
 */
public class AuditLogServletFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(AuditLogServletFilter.class);

	private final AuditCaptureService captureService;
	private final AuditLogProperties properties;
	private final AntPathMatcher pathMatcher = new AntPathMatcher();

	/** 暂存请求级审计上下文：异步处理启动后，收尾须等到 ASYNC dispatch 回来才能进行。 */
	private static final String ASYNC_CONTEXT_ATTR = AuditLogServletFilter.class.getName() + ".asyncContext";

	/**
	 * 暂存被包装过的请求。
	 *
	 * <p>ASYNC dispatch 传入的是新的 request 对象，其属性表与首次 dispatch 的不同；
	 * 收尾时若不用被包装的请求，读不到缓存的请求体，异步请求的正文会丢失。
	 */
	private static final String ASYNC_REQUEST_ATTR = AuditLogServletFilter.class.getName() + ".asyncRequest";

	/** 标记该请求的审计记录已在异步收尾阶段发布，避免 ASYNC dispatch 与兜底监听器重复发布。 */
	private static final String ASYNC_FINALIZED_ATTR = AuditLogServletFilter.class.getName() + ".asyncFinalized";

	public AuditLogServletFilter(AuditCaptureService captureService, AuditLogProperties properties) {
		this.captureService = captureService;
		this.properties = properties;
		log.info("[audit-log] 审计边界过滤器已创建（order={}，include={}，exclude={}）",
			properties.getCollector().getBoundary().getOrder(),
			properties.getCollector().getBoundary().getIncludePatterns(),
			properties.getCollector().getBoundary().getExcludePatterns());
	}

	/**
	 * 必须处理 ASYNC dispatch，否则异步请求（{@code Callable}/{@code DeferredResult}/
	 * {@code WebAsyncTask}/SSE）会产生系统性错误的审计记录。
	 *
	 * <p>{@code startAsync()} 返回后初始线程立即退出，此刻收尾记录到的状态码是占位 200、
	 * 耗时只算到 {@code startAsync()}、事务结果尚未写回；而 ASYNC 二次进入默认被
	 * {@code OncePerRequestFilter} 跳过，记录再也得不到修正。
	 * 这种"假成功 + 假耗时"的记录比缺失记录更危险。
	 */
	@Override
	protected boolean shouldNotFilterAsyncDispatch() {
		return false;
	}

	/**
	 * ERROR dispatch 也必须进入过滤器链：{@code OncePerRequestFilter} 默认跳过该 dispatch，
	 * 而容器处理错误映射后状态码会变化，跳过就使记录停在 REQUEST dispatch 结束时的状态码上。
	 */
	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String uri = request.getRequestURI();
		AuditLogProperties.Boundary boundary = properties.getCollector().getBoundary();
		List<String> excludes = boundary.getExcludePatterns();
		if (excludes != null) {
			for (String pattern : excludes) {
				if (pattern != null && !pattern.isBlank() && pathMatcher.match(pattern.trim(), uri)) {
					if (log.isDebugEnabled()) {
						log.debug("[audit-log] 跳过审计（命中排除模式 {}）：{}", pattern, uri);
					}
					return true;
				}
			}
		}
		List<String> includes = boundary.getIncludePatterns();
		if (includes == null || includes.isEmpty()) {
			return false;
		}
		for (String pattern : includes) {
			if (pattern != null && !pattern.isBlank() && pathMatcher.match(pattern.trim(), uri)) {
				return false;
			}
		}
		if (log.isDebugEnabled()) {
			log.debug("[audit-log] 跳过审计（未命中任何 include 模式）：{}", uri);
		}
		return true;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// ASYNC dispatch 是同一请求的第二次进入，不是新请求。
		//
		// 此处若再调用 begin() 会创建全新上下文（新的 eventTime、空的 builder），后果是：
		//   1. durationMillis 从第二次进入起算，异步请求的耗时被严重低估；
		//   2. 语义通道在 ASYNC dispatch 再填一遍字段，与首次记录叠加成两条。
		//
		// 因此 ASYNC dispatch 只复用首次暂存的上下文做收尾。
		boolean asyncDispatch = request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC;
		if (asyncDispatch) {
			Object stored = request.getAttribute(ASYNC_CONTEXT_ATTR);
			Object storedRequest = request.getAttribute(ASYNC_REQUEST_ATTR);
			// 收尾以被包装的请求为准：请求体缓存在它身上，ASYNC dispatch 的新 request 取不到
			HttpServletRequest payloadSource =
				storedRequest instanceof HttpServletRequest original ? original : request;
			Throwable businessError = null;
			// 异步派发运行在另一个线程上：首次派发写入的上下文对当前线程不可见，
			// 必须重新绑定，否则该次派发中的语义字段会全部缺失
			if (stored instanceof AuditRecordContext deferredContext) {
				AuditRequestContextHolder.set(request, deferredContext);
			}
			try {
				chain.doFilter(request, response);
			}
			catch (Throwable error) {
				businessError = error;
				throw error;
			}
			finally {
				if (stored instanceof AuditRecordContext deferred) {
					// 传入异步过程中的真实异常（无异常时为 null），否则 outcome/error 字段失真
					finalizeNow(deferred, payloadSource, response, businessError);
				}
			}
			return;
		}

		AuditRecordContext context = null;
		HttpServletRequest effectiveRequest = request;
		try {
			int maxCache = properties.getCollector().getBoundary().getMaxCachedBodyBytes();
			if (maxCache > 0 && hasBody(request)) {
				// 必须用 tee（透传 + 旁路缓存），不能用 wrap：wrap 会先把请求体读入内存，
				// 超过缓存上限的部分被截断后才交给业务，造成静默数据损坏。
				effectiveRequest = CachedBodyHttpServletRequest.tee(request, maxCache);
			}
			context = captureService.begin(viewOf(effectiveRequest));
			// 请求视图挂到请求属性上：语义通道位于 HandlerAdapter 内，据此解析资源 ID
			effectiveRequest.setAttribute(ServletAuditRequestView.ATTRIBUTE_KEY,
				new ServletAuditRequestView(effectiveRequest));
			// 上下文写入请求属性：过滤器运行在 RequestContextHolder 初始化之前，
			// 不能依赖 Spring 的请求属性容器，否则写入会落空且无法察觉
			AuditRequestContextHolder.set(effectiveRequest, context);
		}
		catch (Throwable error) {
			// 采集起点失败不能让业务请求失败
			captureService.reportFailure("begin", "审计上下文初始化失败", error);
			context = null;
		}

		Throwable businessError = null;
		try {
			chain.doFilter(effectiveRequest, response);
		}
		catch (Throwable error) {
			businessError = error;
			throw error;
		}
		finally {
			if (context != null) {
				boolean async = false;
				try {
					async = effectiveRequest.isAsyncStarted();
				}
				catch (Throwable ignored) {
					// 容器未实现该能力时按同步处理
				}
				if (async) {
					// 异步处理已启动：此刻收尾会得到错误记录（状态码为占位 200、耗时只到
					// startAsync()、事务结果尚未写回），改为暂存上下文，等 ASYNC dispatch 回来再收尾。
					// 同时注册 AsyncListener 兜底：超时、出错或容器直接完成时同样需要收尾点，
					// 否则该条审计记录丢失。
					deferForAsync(effectiveRequest, context);
				}
				else {
					finalizeNow(context, effectiveRequest, response, businessError);
				}
			}
		}
	}

	/**
	 * 发布记录并清理上下文，供同步路径与 ASYNC dispatch 复用。
	 *
	 * <p>同一请求上以 {@link #ASYNC_FINALIZED_ATTR} 去重：属性已存在时直接返回，重复调用不会
	 * 产生第二条记录。无论发布成功与否都清理线程上的审计上下文，避免污染后续请求。
	 *
	 * @param context 采集上下文，为 {@code null} 时不发布
	 * @param businessError 业务抛出的异常，无异常时为 {@code null}
	 */
	private void finalizeNow(AuditRecordContext context, HttpServletRequest request, HttpServletResponse response,
			Throwable businessError) {
		if (context == null || request.getAttribute(ASYNC_FINALIZED_ATTR) != null) {
			return;
		}
		request.setAttribute(ASYNC_FINALIZED_ATTR, Boolean.TRUE);
		try {
			captureService.finish(context, viewOf(request), new ServletAuditResponseView(response), businessError);
		}
		catch (Throwable error) {
			captureService.reportFailure("finish", "审计记录发布失败", error);
		}
		finally {
			AuditRequestContextHolder.clear(request);
		}
	}

	/**
	 * 异步路径：暂存上下文与包装请求，等 ASYNC dispatch 或兜底监听器收尾。
	 *
	 * <p>监听器不可省略：{@code startAsync()} 之后容器可能直接完成（不再走第二次 dispatch）
	 * 或超时、出错，仅靠 ASYNC dispatch 会错过收尾时机，导致整条审计记录丢失。
	 */
	private void deferForAsync(HttpServletRequest request, AuditRecordContext context) {
		request.setAttribute(ASYNC_CONTEXT_ATTR, context);
		// 一并暂存包装请求本身：收尾读取缓存的请求体必须经由它
		request.setAttribute(ASYNC_REQUEST_ATTR, request);
		try {
			jakarta.servlet.AsyncContext asyncContext = request.getAsyncContext();
			if (asyncContext == null) {
				return;
			}
			asyncContext.addListener(new jakarta.servlet.AsyncListener() {
				@Override
				public void onComplete(jakarta.servlet.AsyncEvent event) {
					finalizeAsync(event, null);
				}

				@Override
				public void onTimeout(jakarta.servlet.AsyncEvent event) {
					finalizeAsync(event, null);
				}

				@Override
				public void onError(jakarta.servlet.AsyncEvent event) {
					finalizeAsync(event, event.getThrowable());
				}

				@Override
				public void onStartAsync(jakarta.servlet.AsyncEvent event) {
					// 重新进入异步后监听器不再生效，必须重新注册以保留兜底收尾点
					event.getAsyncContext().addListener(this);
				}
			});
		}
		catch (Throwable error) {
			// 注册失败不影响业务请求，但必须上报：缺失兜底收尾点意味着异步异常或超时场景可能丢记录
			captureService.reportFailure("async-listener",
				"异步审计兜底监听器注册失败，异步请求的审计记录可能在异常/超时场景丢失", error);
		}
	}

	/**
	 * 兜底收尾：由 {@code AsyncListener} 在完成、超时或出错时调用。
	 *
	 * <p>幂等由 {@link #ASYNC_FINALIZED_ATTR} 保证，可能与 ASYNC dispatch 的收尾竞争同一请求，
	 * 重复调用不会产生第二条记录。
	 *
	 * @param error 异步过程中的异常，完成或超时时为 {@code null}
	 */
	private void finalizeAsync(jakarta.servlet.AsyncEvent event, Throwable error) {
		jakarta.servlet.ServletRequest suppliedRequest = event.getSuppliedRequest();
		jakarta.servlet.ServletResponse suppliedResponse = event.getSuppliedResponse();
		if (!(suppliedRequest instanceof HttpServletRequest request)) {
			return;
		}
		Object stored = request.getAttribute(ASYNC_CONTEXT_ATTR);
		Object alreadyFinalized = request.getAttribute(ASYNC_FINALIZED_ATTR);
		if (!(stored instanceof AuditRecordContext context)) {
			return;
		}
		if (alreadyFinalized != null) {
			return;
		}
		if (!(suppliedResponse instanceof HttpServletResponse httpResponse)) {
			return;
		}
		request.setAttribute(ASYNC_FINALIZED_ATTR, Boolean.TRUE);
		try {
			captureService.finish(context, viewOf(request), new ServletAuditResponseView(httpResponse), error);
		}
		catch (Throwable finishError) {
			captureService.reportFailure("finish", "异步审计记录发布失败", finishError);
		}
		finally {
			AuditRequestContextHolder.clear(request);
		}
	}

	/** 构造请求视图：环境无关的采集编排只依赖该抽象。 */
	private static io.github.pkyit.auditlog.spring.collect.AuditRequestView viewOf(HttpServletRequest request) {
		return new ServletAuditRequestView(request);
	}

	private boolean hasBody(HttpServletRequest request) {
		String method = request.getMethod();
		if (!("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
				|| "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method))) {
			return false;
		}
		long length = request.getContentLengthLong();
		return length != 0L;
	}
}
