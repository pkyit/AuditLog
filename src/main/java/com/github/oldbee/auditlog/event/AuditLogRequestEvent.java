package com.github.oldbee.auditlog.event;

import com.github.oldbee.auditlog.model.AuditLog;
import org.springframework.context.ApplicationEvent;

/**
 * 审计日志请求事件
 */
public class AuditLogRequestEvent extends ApplicationEvent {

    private final AuditLog auditLog;

    public AuditLogRequestEvent(AuditLog auditLog) {
        super(auditLog);
        this.auditLog = auditLog;
    }

    public AuditLog getAuditLog() {
        return auditLog;
    }
}