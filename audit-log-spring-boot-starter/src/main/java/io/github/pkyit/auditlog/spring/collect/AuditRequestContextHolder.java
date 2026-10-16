package io.github.pkyit.auditlog.spring.collect;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 请求级审计上下文的存取。
 *
 * <p><b>承载方式</b>：以 Servlet 请求属性为准，另以 ThreadLocal 兜底。读取时先查当前请求属性，
 * 取不到再回退到 ThreadLocal。
 *
 * <p><b>为什么不能只用 Spring 的 {@code RequestContextHolder}</b>：它由
 * {@code RequestContextFilter} 或 {@code DispatcherServlet} 填充，而审计边界过滤器
 * 默认运行在更早的位置（order 早于前者）。若把上下文存进 {@code RequestContextHolder}，
 * 过滤器调用时它尚未初始化，写入就会落空；而过滤器无法感知这一点，表现为语义字段
 * 全部缺失却没有任何报错。请求属性在过滤器阶段必然可用，是唯一不受过滤器顺序影响的载体。
 *
 * <p>ThreadLocal 兜底的用途是覆盖请求属性不可见的场景（例如异步派发）。
 * 两者都必须在请求结束时清理，避免线程池复用导致上下文串请求。
 */
public final class AuditRequestContextHolder {

	private static final Logger log = LoggerFactory.getLogger(AuditRequestContextHolder.class);

	private static final String ATTRIBUTE = AuditRequestContextHolder.class.getName() + ".CONTEXT";

	private static final ThreadLocal<AuditRecordContext> FALLBACK = new ThreadLocal<>();

	private static volatile boolean warnedMissingRequest;

	private AuditRequestContextHolder() {
	}

	/**
	 * 绑定当前请求的审计上下文。
	 *
	 * @param request 当前请求；为 {@code null} 时仅写入 ThreadLocal 并告警
	 * @param context 审计上下文
	 */
	public static void set(HttpServletRequest request, AuditRecordContext context) {
		if (request == null) {
			// 拿不到请求意味着上下文只能在当前线程内可见，异步派发时会丢失语义字段。
			// 这是明确的降级，必须留下痕迹而不是默默继续。
			if (!warnedMissingRequest) {
				warnedMissingRequest = true;
				log.warn("[audit-log] 绑定审计上下文时未取得 HttpServletRequest，"
					+ "上下文仅对当前线程可见；异步派发场景下语义字段可能缺失");
			}
		}
		else {
			request.setAttribute(ATTRIBUTE, context);
		}
		FALLBACK.set(context);
	}

	/** 获取当前请求的审计上下文；不在受审计的请求内时返回 {@code null}。 */
	public static AuditRecordContext get() {
		HttpServletRequest request = currentRequest();
		if (request != null) {
			Object value = request.getAttribute(ATTRIBUTE);
			if (value instanceof AuditRecordContext context) {
				return context;
			}
		}
		return FALLBACK.get();
	}

	/**
	 * 清理上下文。
	 *
	 * @param request 当前请求；为 {@code null} 时只清理 ThreadLocal
	 */
	public static void clear(HttpServletRequest request) {
		if (request != null) {
			request.removeAttribute(ATTRIBUTE);
		}
		FALLBACK.remove();
	}

	/** 取当前线程绑定的请求；不在 HTTP 请求线程内时返回 {@code null}。 */
	private static HttpServletRequest currentRequest() {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (attributes instanceof ServletRequestAttributes servletAttributes) {
			return servletAttributes.getRequest();
		}
		return null;
	}
}
