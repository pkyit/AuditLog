package io.github.pkyit.auditlog.spring.servlet;

import io.github.pkyit.auditlog.spring.collect.AuditResponseView;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@link AuditResponseView} 的 Servlet 实现。
 *
 * <p>{@link #status()} 返回的可能是尚未最终确定的状态码：异常沿过滤器链向上抛出时容器还未渲染
 * 错误页，此时 {@code HttpServletResponse.getStatus()} 仍为占位的 200。
 *
 * <p>因此本类不做"状态码是否有效"的判断，该判断由编排层结合异常信息完成，
 * 否则会记出自相矛盾的结果。
 */
public class ServletAuditResponseView implements AuditResponseView {

	private final HttpServletResponse response;

	public ServletAuditResponseView(HttpServletResponse response) {
		this.response = response;
	}

	/** 原始 Servlet 响应（仅供 Servlet 适配器自身使用）。 */
	public HttpServletResponse nativeResponse() {
		return response;
	}

	@Override
	public int status() {
		return response == null ? -1 : response.getStatus();
	}

	@Override
	public String environment() {
		return "servlet";
	}
}
