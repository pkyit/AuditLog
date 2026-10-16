package com.github.pkyit.module.audit.webmvc;

import com.github.pkyit.module.audit.event.AuditLogEvent;
import com.github.pkyit.module.audit.model.bo.MethodAuditLogBO;
import com.github.pkyit.module.audit.model.entity.AuditLogDO;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AuditLogServletInvocableHandlerMethod extends ServletInvocableHandlerMethod {
	private static final Logger log = LoggerFactory.getLogger(AuditLogServletInvocableHandlerMethod.class);

	private final WebApplicationContext context;
	private final LinkedHashMap<Method, MethodAuditLogBO> auditLogMethods;

	public AuditLogServletInvocableHandlerMethod(HandlerMethod handler, WebApplicationContext context) {
		super(handler);
		this.context = context;
		this.auditLogMethods = (LinkedHashMap<Method, MethodAuditLogBO>) context.getBean("auditLogMethods");
	}

	/**
	 * Invoke the handler method with the given argument values.
	 *
	 * @param args
	 */
	@Override
	protected Object doInvoke(Object... args) throws Exception {

		if (!auditLogMethods.containsKey(getMethod())) {
			// 如果当前的请求不在 auditLogMethods集合中，说明不需要做日志审计，则直接调用原方法
			return super.doInvoke(args);
		}

		MethodAuditLogBO methodAuditLog = auditLogMethods.get(getMethod());

		return invokeAndLog(methodAuditLog, args);
	}


	/**
	 * 执行方法并记录审计日志
	 */
	private Object invokeAndLog(MethodAuditLogBO methodLog, Object[] args) throws Exception {
		HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
		long startTime = System.nanoTime(); // 记录开始时间
		Object returnValue = null;
		Exception exception = null;
		try {
			returnValue = super.doInvoke(args);
		} catch (Exception e) {
			exception = e;
			throw e; // 重新抛出异常，不影响原方法的行为
		} finally {
			String userId = request.getHeader("userId"); // 记录用户id
			String clientIp = request.getRemoteAddr(); // 这里简单记录最后一跳的IP地址，实际生产中，这里应该记录用户访问时的IP地址
			String requestType = request.getMethod(); // 请求类型
			String operationName = methodLog.getName(); // 操作名称
			String requestUri = request.getRequestURI(); // 请求URI
			String exceptionInfo = exception != null ? exception.getMessage() : "";

			// 获取 HttpServletResponse 对象并获取实际的状态码
//			HttpServletResponse response = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getResponse();
			int responseCode = exception != null ? 500 : 200;

			// 执行时长, 单位毫秒
			long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
			String requestHeaders = methodLog.isLogHeaders() ? getFilteredHeaders(request, methodLog.getSensitiveParams()) : "";
			String requestParams = methodLog.isLogRequestParams() ? Stream.of(args).map(Object::toString).collect(Collectors.joining(", ")) : "";
			String responseParams = methodLog.isLogResponseParams() ? (returnValue != null ? returnValue.toString() : "") : "";

			// 发布审计日志事件,(这个id是模拟id，后期不妨加上链路追踪的id)
			context.publishEvent(new AuditLogEvent(new AuditLogDO(121212L, userId, operationName, clientIp, requestUri, requestType, requestParams,
				requestHeaders, responseParams, responseCode, duration, exceptionInfo, new Date())));
		}

		return returnValue;
	}

	/**
	 * 获取过滤后的 Headers（排除敏感 Headers）
	 */
	private String getFilteredHeaders(HttpServletRequest request, Set<String> sensitiveHeaders) {
		return Collections.list(request.getHeaderNames())
			.stream()
			.filter(headerName -> !sensitiveHeaders.contains(headerName))
			.map(name -> name + ":" + Collections.list(request.getHeaders(name)))
			.collect(Collectors.joining(", "));
	}

}
