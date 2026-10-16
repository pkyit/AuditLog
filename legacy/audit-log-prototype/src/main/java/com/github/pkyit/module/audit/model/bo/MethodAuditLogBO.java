package com.github.pkyit.module.audit.model.bo;

import java.util.Objects;
import java.util.Set;

public class MethodAuditLogBO {

	private String name;

	private boolean logHeaders;

	private boolean logRequestParams;

	private boolean logResponseParams;

	private Set<String> sensitiveParams;


	public MethodAuditLogBO(String name, boolean logHeaders, boolean logRequestParams, boolean logResponseParams, Set<String> sensitiveParams) {
		this.name = name;
		this.logHeaders = logHeaders;
		this.logRequestParams = logRequestParams;
		this.logResponseParams = logResponseParams;
		this.sensitiveParams = sensitiveParams;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public boolean isLogHeaders() {
		return logHeaders;
	}

	public void setLogHeaders(boolean logHeaders) {
		this.logHeaders = logHeaders;
	}

	public boolean isLogRequestParams() {
		return logRequestParams;
	}

	public void setLogRequestParams(boolean logRequestParams) {
		this.logRequestParams = logRequestParams;
	}

	public boolean isLogResponseParams() {
		return logResponseParams;
	}

	public void setLogResponseParams(boolean logResponseParams) {
		this.logResponseParams = logResponseParams;
	}

	public Set<String> getSensitiveParams() {
		return sensitiveParams;
	}

	public void setSensitiveParams(Set<String> sensitiveParams) {
		this.sensitiveParams = sensitiveParams;
	}

	@Override
	public boolean equals(Object o) {
		if (o == null || getClass() != o.getClass()) return false;
		MethodAuditLogBO that = (MethodAuditLogBO) o;
		return logHeaders == that.logHeaders && logRequestParams == that.logRequestParams && logResponseParams == that.logResponseParams && Objects.equals(name, that.name) && Objects.equals(sensitiveParams, that.sensitiveParams);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, logHeaders, logRequestParams, logResponseParams, sensitiveParams);
	}

	@Override
	public String toString() {
		return "MethodAuditLogBO{" +
			"name='" + name + '\'' +
			", logHeaders=" + logHeaders +
			", logRequestParams=" + logRequestParams +
			", logResponseParams=" + logResponseParams +
			", sensitiveParams=" + sensitiveParams +
			'}';
	}
}
