package io.github.pkyit.auditlog.spring.method;

import io.github.pkyit.auditlog.spring.collect.AuditCaptureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/**
 * 注册自定义 HandlerAdapter，作为语义通道的接入点。
 *
 * <p>该扩展位一个应用只能有一份生效，因此仅在 {@code audit.log.collector.method.enabled=true}
 * 时注册本 Bean：纯边界采集模式下不占用 {@code WebMvcRegistrations}，
 * 以免覆盖宿主自身的同类型注册。
 */
public class AuditLogWebMvcRegistrations implements WebMvcRegistrations {

	private static final Logger log = LoggerFactory.getLogger(AuditLogWebMvcRegistrations.class);

	private final AuditCaptureService captureService;

	public AuditLogWebMvcRegistrations(AuditCaptureService captureService) {
		this.captureService = captureService;
	}

	@Override
	public RequestMappingHandlerAdapter getRequestMappingHandlerAdapter() {
		log.info("[audit-log] 已注册审计语义通道（自定义 RequestMappingHandlerAdapter）");
		return new AuditLogRequestMappingHandlerAdapter(captureService);
	}
}
