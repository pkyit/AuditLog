package com.github.oldbee.auditlog.web;

import com.github.oldbee.auditlog.model.AuditLogConfig;
import com.github.oldbee.auditlog.model.MethodLog;
import com.github.oldbee.auditlog.event.AuditLogRequestEvent;
import com.github.oldbee.auditlog.model.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 自定义HandlerMethod，用于在调用Controller方法前后记录审计日志
 */
public class AuditLogHandlerMethod extends ServletInvocableHandlerMethod {

    private static final Logger log = LoggerFactory.getLogger("ApiAuditLog");
    private final ApplicationContext context;
    private final AuditLogConfig auditLogConfig;

    public AuditLogHandlerMethod(HandlerMethod handlerMethod, ApplicationContext context) {
        super(handlerMethod);
        this.context = context;
        this.auditLogConfig = context.getBean(AuditLogConfig.class);
    }

    @Override
    protected Object doInvoke(Object... args) throws Exception {
        // 如果审计日志未启用，直接执行原方法
        if (!auditLogConfig.isEnable()) {
//            log.debug("审计日志全局禁用，直接执行方法");
            return super.doInvoke(args);
        }

        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        MethodLog methodLog = auditLogConfig.getMethodLogs().get(getBridgedMethod());

        // 添加调试日志
//        log.debug("处理方法: {}#{}", getBeanType().getName(), getMethod().getName());
//        log.debug("methodLog: {}", methodLog);
//        log.debug("methodLog != null: {}", methodLog != null);
        if (methodLog != null) {
//            log.debug("methodLog.isEnable(): {}", methodLog.isEnable());
        }
//        log.debug("auditLogConfig.getMethodLogs().size(): {}", auditLogConfig.getMethodLogs().size());

		// 如果方法明确配置了 @Log(enable = "false")，则不记录日志
		if (methodLog != null && !methodLog.isEnable()) {
//			log.debug("方法明确禁用日志记录，跳过日志");
			return super.doInvoke(args);
		}

		// 如果当前方法配置了审计日志且启用，则记录日志
        if (methodLog != null && methodLog.isEnable()) {
//            log.debug("启用方法级日志记录");
            return invokeAndLog(methodLog, request, args);
        }

        // 审计日志是系统底线要求，无论什么情况下都必须记录（仅当未明确禁用时）
//        log.debug("使用全局配置记录日志");
        return invokeAndLogGlobal(request, args);
    }

    /**
     * 执行方法并记录审计日志（方法级配置）
     */
    private Object invokeAndLog(MethodLog methodLog, HttpServletRequest request, Object[] args) throws Exception {
        long startTime = System.nanoTime();
        Object returnValue = null;
        Exception exception = null;
        try {
            returnValue = super.doInvoke(args);
        } catch (Exception e) {
            exception = e;
            throw e; // 重新抛出异常，不影响原方法的行为
        } finally {
            long takeTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

            // 审计日志是系统底线要求，无论什么情况下都必须记录
            String headers = getFilteredHeaders(request, methodLog.getSensitiveHeaders());
            AuditLog auditLog = new AuditLog(
                    request.getRemoteAddr(),
                    getBeanType().getName(),
                    getMethod().getName(),
                    returnValue,
                    args == null ? null : Stream.of(args).collect(Collectors.toList()),
                    request.getRequestURI(),
                    request.getMethod(),
                    headers,
                    takeTime,
                    exception == null,
                    exception != null ? exception.getMessage() : null
            );
//            log.info("请求详细信息: {}", auditLog);
            context.publishEvent(new AuditLogRequestEvent(auditLog));
        }

        return returnValue;
    }

    /**
     * 执行方法并记录全局审计日志（无方法级配置时）
     */
    private Object invokeAndLogGlobal(HttpServletRequest request, Object[] args) throws Exception {
        long startTime = System.nanoTime();
        Object returnValue = null;
        Exception exception = null;
        try {
            returnValue = super.doInvoke(args);
        } catch (Exception e) {
            exception = e;
            throw e; // 重新抛出异常，不影响原方法的行为
        } finally {
            long takeTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

            // 审计日志是系统底线要求，无论什么情况下都必须记录
            String headers = getFilteredHeaders(request, this.auditLogConfig.getSensitiveHeaders());
            AuditLog auditLog = new AuditLog(
                    request.getRemoteAddr(),
                    getBeanType().getName(),
                    getMethod().getName(),
                    returnValue,
                    args == null ? null : Stream.of(args).collect(Collectors.toList()),
                    request.getRequestURI(),
                    request.getMethod(),
                    headers,
                    takeTime,
                    exception == null,
                    exception != null ? exception.getMessage() : null
            );
//            log.info("请求详细信息: {}", auditLog);
            context.publishEvent(new AuditLogRequestEvent(auditLog));
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
