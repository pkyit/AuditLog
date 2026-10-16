package io.github.pkyit.auditlog.core.api;

/**
 * 框架内置指标名。
 *
 * <p>命名约定：{@code audit.<域>.<事件>}。所有「可能丢数据」的路径都必须有对应计数器。
 */
public final class AuditMetricNames {

	private AuditMetricNames() {
	}

	/** 采集到的记录数。 */
	public static final String COLLECTED = "audit.collect.total";

	/** 因未命中注解/被排除而跳过的请求数。 */
	public static final String SKIPPED = "audit.collect.skipped";

	/** 采集期发生异常的请求数（不影响业务，但必须可观测）。 */
	public static final String COLLECT_ERROR = "audit.collect.error";

	/** 命中脱敏规则的字段/内容次数。 */
	public static final String MASKED = "audit.mask.hits";

	/** 入队成功的记录数。 */
	public static final String ENQUEUED = "audit.pipeline.enqueued";

	/** 队列满而触发背压的次数。 */
	public static final String BACKPRESSURE = "audit.pipeline.backpressure";

	/** 因背压策略被直接丢弃的记录数（最严重的指标，应告警）。 */
	public static final String DROPPED = "audit.pipeline.dropped";

	/** 降级写入本地 WAL 的记录数。 */
	public static final String JOURNALED = "audit.pipeline.journaled";

	/** WAL 自身也拒绝写入的记录数（需要人工介入）。 */
	public static final String JOURNAL_REJECTED = "audit.pipeline.journal.rejected";

	/** WAL 重放成功的记录数。 */
	public static final String REPLAYED = "audit.pipeline.replayed";

	/** 成功投递到存储的记录数。 */
	public static final String DELIVERED = "audit.store.delivered";

	/** 投递失败次数（含重试）。 */
	public static final String STORE_FAILED = "audit.store.failed";

	/** 重试次数。 */
	public static final String STORE_RETRIED = "audit.store.retried";

	/** 进入死信的记录数。 */
	public static final String DEADLETTER = "audit.store.deadletter";

	/** 存储自身报告不健康。 */
	public static final String STORE_UNHEALTHY = "audit.store.unhealthy";

	/** 当前队列深度（瞬时值）。 */
	public static final String QUEUE_DEPTH = "audit.pipeline.queue.depth";

	/** 当前 WAL 待重放数量（瞬时值）。 */
	public static final String JOURNAL_PENDING = "audit.pipeline.journal.pending";

	/** 采集耗时（毫秒）。 */
	public static final String COLLECT_LATENCY = "audit.collect.latency";

	/** 存储投递耗时（毫秒）。 */
	public static final String STORE_LATENCY = "audit.store.latency";

	/** 保留策略清理掉的记录/文件数。 */
	public static final String RETENTION_CLEANED = "audit.retention.cleaned";
}
