package com.github.oldbee.auditlog.model;

import java.util.Set;

/**
 * 方法级别的日志配置信息
 */
public class MethodLog {
    private boolean enable;
    private boolean logHeaders;
    private boolean logRequest;
    private boolean logResponse;
    private Set<String> sensitiveHeaders;

    public MethodLog() {}

    public MethodLog(boolean enable, boolean logHeaders, boolean logRequest,
                     boolean logResponse, Set<String> sensitiveHeaders) {
        this.enable = enable;
        this.logHeaders = logHeaders;
        this.logRequest = logRequest;
        this.logResponse = logResponse;
        this.sensitiveHeaders = sensitiveHeaders;
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
}