package io.github.pkyit.auditlog.spring.method;

import io.github.pkyit.auditlog.spring.collect.AuditCaptureService;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod;

/**
 * 自定义处理器适配器：把 Handler 方法包装为审计感知的 {@link AuditLogHandlerMethod}。
 *
 * <p>{@code WebMvcRegistrations} 在应用中只能有一份生效：宿主应用或其他框架组件同样提供该接口时，
 * 两者互相覆盖。因此语义通道必须设计为可关闭、且非唯一采集路径 —— 即使被覆盖，
 * 边界通道仍保证认证失败、403、404 与成功请求全部被采集，仅缺失操作名、动作类型等语义字段，
 * 属于明确降级而非静默丢失数据。
 */
public class AuditLogRequestMappingHandlerAdapter extends RequestMappingHandlerAdapter {

	private final AuditCaptureService captureService;

	public AuditLogRequestMappingHandlerAdapter(AuditCaptureService captureService) {
		this.captureService = captureService;
	}

	@Override
	protected ServletInvocableHandlerMethod createInvocableHandlerMethod(HandlerMethod handlerMethod) {
		return new AuditLogHandlerMethod(handlerMethod, captureService);
	}
}
