package io.github.pkyit.auditlog.core.api;

import java.util.List;

/**
 * 审计链路失败处理 SPI。
 *
 * <p>与普通应用日志的区别：这里报告的是审计能力自身受损（队列满、存储不可用、WAL 拒绝），
 * 属于需要触发告警的事件，而不是可忽略的运行日志。默认实现打 WARN/ERROR 日志，
 * 生产环境应替换为短信/IM/告警平台推送。
 */
@FunctionalInterface
public interface AuditFailureHandler {

	/**
	 * 处理一次审计失败。
	 *
	 * <p>实现 MUST NOT 抛异常：失败上报发生在采集与投递链路内部，
	 * 抛出异常会顶替原始失败原因，导致真正的故障位置不可知。
	 *
	 * @param stage   失败阶段（{@code enqueue} / {@code journal} / {@code store} / {@code serialize} / {@code mask}）
	 * @param message 人类可读描述
	 * @param records 受影响记录（可能为空，但不应为 {@code null}）
	 * @param error   异常；失败原因非异常时为 {@code null}
	 */
	void onFailure(String stage, String message, List<?> records, Throwable error);

	/** 默认实现：写 ERROR 日志；替换实现时应保证与日志同等可靠。 */
	static AuditFailureHandler logging() {
		return new AuditFailureHandler() {
			private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuditFailureHandler.class);

			@Override
			public void onFailure(String stage, String message, List<?> records, Throwable error) {
				log.error("[audit-log] 审计链路失败 stage={} message={} affected={}", stage, message,
					records == null ? 0 : records.size(), error);
			}
		};
	}
}
