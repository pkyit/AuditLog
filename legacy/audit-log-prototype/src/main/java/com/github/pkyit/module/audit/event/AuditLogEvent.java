package com.github.pkyit.module.audit.event;

import com.github.pkyit.module.audit.model.entity.AuditLogDO;
import org.springframework.context.ApplicationEvent;

public class AuditLogEvent extends ApplicationEvent {

	private final AuditLogDO auditLogDO;

	public AuditLogEvent(AuditLogDO auditLogDO) {
		super(auditLogDO);
		this.auditLogDO = auditLogDO;
	}

	public AuditLogDO getAuditLogDO() {
		return auditLogDO;
	}
}
