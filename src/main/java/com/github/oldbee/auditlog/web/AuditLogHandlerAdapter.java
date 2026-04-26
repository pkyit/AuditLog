package com.github.oldbee.auditlog.web;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod;

/**
 * 自定义HandlerAdapter，用于拦截Controller方法调用
 */
public class AuditLogHandlerAdapter extends RequestMappingHandlerAdapter {

    @Override
    protected ServletInvocableHandlerMethod createInvocableHandlerMethod(HandlerMethod handlerMethod) {
        return new AuditLogHandlerMethod(handlerMethod, getApplicationContext());
    }
}