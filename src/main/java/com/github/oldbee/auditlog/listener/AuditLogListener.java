package com.github.oldbee.auditlog.listener;

import com.github.oldbee.auditlog.event.AuditLogRequestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 审计日志事件监听器
 */
@Component
public class AuditLogListener implements ApplicationListener<AuditLogRequestEvent> {

    private static final Logger log = LoggerFactory.getLogger(AuditLogListener.class);

    @Async // 使用Spring默认的异步执行器
    @Override
    public void onApplicationEvent(AuditLogRequestEvent event) {
        // 可以在这里处理审计日志事件，例如保存到数据库或发送到消息队列
        // 这里只是简单地记录日志
        log.info("Audit Log Event: {}", event.getAuditLog());
    }
}
