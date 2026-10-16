package com.github.pkyit.module.audit.listener;

import com.github.pkyit.module.audit.event.AuditLogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 审计日志事件监听器
 */
@Component
public class AuditLogListener implements ApplicationListener<AuditLogEvent> {

	private static final Logger log = LoggerFactory.getLogger(AuditLogListener.class);

	@Override
	@Async//("auditLogTaskExecutor") // 使用指定的线程池异步执行
	public void onApplicationEvent(AuditLogEvent event) {
		// 可以在这里处理审计日志事件，例如保存到数据库或发送到消息队列
		// 这里只是简单地记录日志
		log.info("模拟打印日志审计事件: {}", event.getAuditLogDO());
	}
}
