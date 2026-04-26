package com.github.oldbee.auditlog.model;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/**
 * 审计日志配置信息
 */
public class AuditLogConfig {
    private boolean enable;
    private boolean logHeaders;
    private boolean logRequest;
    private boolean logResponse;
    private Set<String> sensitiveHeaders;
    private Map<Method, MethodLog> methodLogs;

    public AuditLogConfig() {}

    public AuditLogConfig(boolean enable, boolean logHeaders, boolean logRequest,
                          boolean logResponse, Set<String> sensitiveHeaders,
                          Map<Method, MethodLog> methodLogs) {
        this.enable = enable;
        this.logHeaders = logHeaders;
        this.logRequest = logRequest;
        this.logResponse = logResponse;
        this.sensitiveHeaders = sensitiveHeaders;
        this.methodLogs = methodLogs;
    }

    // Getters and Setters
    public boolean isEnable() {
        return enable;
    }

    public void setEnable(boolean enable) {
        this.enable = enable;
    }

    public boolean isLogHeaders() {
        return logHeaders;
    }

    public void setLogHeaders(boolean logHeaders) {
        this.logHeaders = logHeaders;
    }

    public boolean isLogRequest() {
        return logRequest;
    }

    public void setLogRequest(boolean logRequest) {
        this.logRequest = logRequest;
    }

    public boolean isLogResponse() {
        return logResponse;
    }

    public void setLogResponse(boolean logResponse) {
        this.logResponse = logResponse;
    }

    public Set<String> getSensitiveHeaders() {
        return sensitiveHeaders;
    }

    public void setSensitiveHeaders(Set<String> sensitiveHeaders) {
        this.sensitiveHeaders = sensitiveHeaders;
    }

    public Map<Method, MethodLog> getMethodLogs() {
        return methodLogs;
    }

    public void setMethodLogs(Map<Method, MethodLog> methodLogs) {
        this.methodLogs = methodLogs;
    }
}