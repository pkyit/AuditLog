package com.github.oldbee.auditlog.model;

import java.util.List;

/**
 * 审计日志实体类
 */
public class AuditLog {
    private String clientIp;
    private String controllerName;
    private String methodName;
    private Object response;
    private List<Object> parameters;
    private String uri;
    private String method;
    private String headers;
    private long duration;
    private boolean success;
    private String errorMessage;

    public AuditLog() {}

    public AuditLog(String clientIp, String controllerName, String methodName,
                   Object response, List<Object> parameters, String uri,
                   String method, String headers, long duration, boolean success, String errorMessage) {
        this.clientIp = clientIp;
        this.controllerName = controllerName;
        this.methodName = methodName;
        this.response = response;
        this.parameters = parameters;
        this.uri = uri;
        this.method = method;
        this.headers = headers;
        this.duration = duration;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    // Getters and Setters
    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getControllerName() {
        return controllerName;
    }

    public void setControllerName(String controllerName) {
        this.controllerName = controllerName;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public Object getResponse() {
        return response;
    }

    public void setResponse(Object response) {
        this.response = response;
    }

    public List<Object> getParameters() {
        return parameters;
    }

    public void setParameters(List<Object> parameters) {
        this.parameters = parameters;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getHeaders() {
        return headers;
    }

    public void setHeaders(String headers) {
        this.headers = headers;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    @Override
    public String toString() {
        return "AuditLog{" +
                "clientIp='" + clientIp + '\'' +
                ", controllerName='" + controllerName + '\'' +
                ", methodName='" + methodName + '\'' +
                ", response=" + response +
                ", parameters=" + parameters +
                ", uri='" + uri + '\'' +
                ", method='" + method + '\'' +
                ", headers='" + headers + '\'' +
                ", duration=" + duration +
                ", success=" + success +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}