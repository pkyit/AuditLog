package com.github.pkyit.module.audit.webmvc;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod;

public class AuditLogRequestMappingHandlerAdapter extends RequestMappingHandlerAdapter {
	@Override
	protected ServletInvocableHandlerMethod createInvocableHandlerMethod(HandlerMethod handlerMethod) {
		return new AuditLogServletInvocableHandlerMethod(handlerMethod, getWebApplicationContext());
	}
}
