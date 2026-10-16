package io.github.pkyit.auditlog.spring.servlet;

import io.github.pkyit.auditlog.spring.collect.AuditRequestView;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AuditRequestView} 的 Servlet 实现：薄封装 {@code HttpServletRequest}。
 *
 * <p>本类只负责取值，不承载审计语义。脱敏、截断、字段规则、主体解析顺序一律由环境无关的
 * {@code AuditCaptureService} 决定，因此各环境的采集行为一致，替换实现只需改变取值方式。
 *
 * <p>取值方法是只读的，不抛异常，缺少目标数据时返回 {@code null} 或空集合。
 */
public class ServletAuditRequestView implements AuditRequestView {

	/**
	 * 请求属性名：语义通道（{@code AuditLogHandlerMethod}）据此取回请求视图。
	 *
	 * <p>语义通道运行在 Spring MVC 的 HandlerAdapter 内，无法访问过滤器栈上的局部变量，
	 * 因此视图经请求属性传递，与审计上下文（{@code AuditRequestContextHolder}）同一途径。
	 */
	public static final String ATTRIBUTE_KEY = ServletAuditRequestView.class.getName();

	private final HttpServletRequest request;

	public ServletAuditRequestView(HttpServletRequest request) {
		this.request = request;
	}

	/**
	 * 原始 Servlet 请求。
	 *
	 * <p>仅供 Servlet 适配器自身使用；编排层依赖 {@link AuditRequestView} 抽象，
	 * 引用具体容器类型会破坏环境无关性。
	 */
	public HttpServletRequest nativeRequest() {
		return request;
	}

	@Override
	public String method() {
		return request.getMethod();
	}

	@Override
	public String uri() {
		return request.getRequestURI();
	}

	@Override
	public String queryString() {
		return request.getQueryString();
	}

	@Override
	public String header(String name) {
		return request.getHeader(name);
	}

	@Override
	public List<String> headers(String name) {
		java.util.Enumeration<String> values = request.getHeaders(name);
		return values == null ? List.of() : Collections.list(values);
	}

	@Override
	public Map<String, List<String>> allHeaders() {
		Map<String, List<String>> result = new LinkedHashMap<>();
		java.util.Enumeration<String> names = request.getHeaderNames();
		if (names == null) {
			return result;
		}
		for (String name : Collections.list(names)) {
			List<String> values = new ArrayList<>(headers(name));
			// 同名多值头（如重复的 Cookie）合并为一个列表；
			// 无值时补空串，保证审计快照中该头可见
			result.put(name, values.isEmpty() ? List.of("") : values);
		}
		return result;
	}

	@Override
	public String parameter(String name) {
		return request.getParameter(name);
	}

	@Override
	public Object attribute(String name) {
		return request.getAttribute(name);
	}

	@Override
	public String sessionId() {
		try {
			jakarta.servlet.http.HttpSession session = request.getSession(false);
			return session == null ? null : session.getId();
		}
		catch (RuntimeException ignored) {
			// 部分容器在特定阶段访问 session 会抛异常；审计不得影响业务，故降级为无 session
			return null;
		}
	}

	@Override
	public String cookie(String name) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (cookie != null && name.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

	@Override
	public String remoteAddress() {
		return request.getRemoteAddr();
	}

	@Override
	public String cachedBody() {
		if (request instanceof CachedBodyHttpServletRequest cached && cached.isCacheable()) {
			return cached.getCachedBodyAsString();
		}
		return null;
	}

	@Override
	public String environment() {
		return "servlet";
	}
}
