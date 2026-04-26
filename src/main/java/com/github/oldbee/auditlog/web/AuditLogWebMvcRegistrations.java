package com.github.oldbee.auditlog.web;

import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/**
 * 注册自定义HandlerAdapter
 */
public class AuditLogWebMvcRegistrations implements WebMvcRegistrations {

    @Override
    public RequestMappingHandlerAdapter getRequestMappingHandlerAdapter() {
        return new AuditLogHandlerAdapter();
    }
}
