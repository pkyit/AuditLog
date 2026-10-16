package com.github.pkyit.module.audit.webmvc;

import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;


public class AuditLogWebMvcRegistrations implements WebMvcRegistrations {

	/**
	 * Return the custom {@link RequestMappingHandlerAdapter} that should be used and
	 * processed by the MVC configuration.
	 *
	 * @return the custom {@link RequestMappingHandlerAdapter} instance
	 */
	@Override
	public RequestMappingHandlerAdapter getRequestMappingHandlerAdapter() {
		return new AuditLogRequestMappingHandlerAdapter();
	}
}
