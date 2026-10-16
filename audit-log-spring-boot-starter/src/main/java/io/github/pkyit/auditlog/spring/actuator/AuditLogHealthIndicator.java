package io.github.pkyit.auditlog.spring.actuator;

import io.github.pkyit.auditlog.core.api.AuditJournal;
import io.github.pkyit.auditlog.core.api.AuditMetricNames;
import io.github.pkyit.auditlog.core.api.AuditStore;
import io.github.pkyit.auditlog.core.pipeline.AuditPipeline;
import io.github.pkyit.auditlog.core.store.LoggingAuditStore;
import io.github.pkyit.auditlog.spring.properties.AuditLogProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * 审计链路健康检查。
 *
 * <p>审计组件最危险的故障模式是静默失效：队列积压、WAL 拒绝写入、存储不可用都不影响
 * 业务接口的返回码，因此审计能力的状态必须经由健康检查暴露给运维与告警系统。
 *
 * <p>状态语义：
 * <ul>
 *   <li>{@code DOWN}：存储不健康、队列积压达到
 *       {@code audit.log.actuator.queue-depth-down-threshold}、或已发生记录丢弃；</li>
 *   <li>{@code UP}（带 {@code warning}）：链路可工作，但存储为非持久化实现，
 *       不满足合规留存要求。</li>
 * </ul>
 *
 * <p>{@code reason} 详情在多个原因同时成立时只保留最后写入的一条：存储不可用、
 * 队列积压、记录丢弃按此顺序依次覆盖。
 */
public class AuditLogHealthIndicator implements HealthIndicator {

	private final AuditPipeline pipeline;
	private final AuditJournal journal;
	private final AuditStore store;
	private final AuditLogProperties properties;

	/**
	 * 创建健康指示器。
	 *
	 * @param pipeline   审计流水线；为 {@code null} 时 {@link #health()} 返回 DOWN，且不再读取其余组件
	 * @param journal    本地兜底日志；为 {@code null} 时待处理数记为 0、健康状态记为正常
	 * @param store      审计存储；为 {@code null} 时视为健康，明细中的名称记为 {@code none}
	 * @param properties 配置来源，用于读取队列积压阈值；不可为 {@code null}，否则
	 *                   {@link #health()} 抛 {@code NullPointerException}
	 */
	public AuditLogHealthIndicator(AuditPipeline pipeline, AuditJournal journal, AuditStore store,
			AuditLogProperties properties) {
		this.pipeline = pipeline;
		this.journal = journal;
		this.store = store;
		this.properties = properties;
	}

	/**
	 * 汇总审计链路健康状态。
	 *
	 * <p>每次调用都重新读取流水线、存储与兜底日志的当前状态，不做缓存；只读，不改变组件状态。
	 *
	 * @return 健康状态；{@code pipeline} 为 {@code null} 时返回 DOWN，明细 {@code reason} 为
	 *         「审计流水线未初始化」
	 */
	@Override
	public Health health() {
		if (pipeline == null) {
			return Health.down().withDetail("reason", "审计流水线未初始化").build();
		}
		int queueDepth = pipeline.queueDepth();
		long dropped = pipeline.droppedCount();
		boolean storeHealthy = store == null || store.isHealthy();
		long journalPending = journal == null ? 0L : journal.pendingCount();

		Health.Builder builder = storeHealthy ? Health.up() : Health.down();
		builder.withDetail("store", store == null ? "none" : store.getName())
			.withDetail("queueDepth", queueDepth)
			.withDetail("queueCapacity", pipeline.config().queueCapacity())
			.withDetail("droppedRecords", dropped)
			.withDetail("lastDeliveredSeq", pipeline.lastDeliveredSeq())
			.withDetail("consumerThreads", pipeline.config().consumerThreads())
			.withDetail("backpressureStrategy", String.valueOf(pipeline.config().strategy()))
			.withDetail("chainEnabled", pipeline.chain() != null && pipeline.chain().isEnabled())
			.withDetail("journalPending", journalPending)
			.withDetail("journalHealthy", journal == null || journal.isHealthy());

		if (!storeHealthy) {
			builder.withDetail("reason", "审计存储不可用，记录将降级写入本地兜底日志");
		}
		if (queueDepth >= properties.getActuator().getQueueDepthDownThreshold()) {
			builder.down().withDetail("reason", "审计队列积压超过阈值 "
				+ properties.getActuator().getQueueDepthDownThreshold());
		}
		if (dropped > 0) {
			builder.down().withDetail("reason", "已发生 " + dropped + " 条审计记录被丢弃，请立即排查");
		}
		if (store instanceof LoggingAuditStore) {
			builder.withDetail("warning", "当前审计存储非持久化（" + AuditMetricNames.DELIVERED
				+ " 仅写入应用日志），不满足合规留存要求");
		}
		return builder.build();
	}
}
