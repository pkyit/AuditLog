package io.github.pkyit.auditlog.core.store;

import io.github.pkyit.auditlog.core.api.AuditMetricNames;
import io.github.pkyit.auditlog.core.api.AuditMetrics;
import io.github.pkyit.auditlog.core.api.AuditStore;
import io.github.pkyit.auditlog.core.model.AuditRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 默认存储：将审计记录写入应用日志。
 *
 * <p>本实现不是合规的审计存储：记录与应用日志混存、随日志滚动被删除，
 * 不满足「审计日志独立留存、防止意外删除」的要求。
 *
 * <p>其存在仅用于保证引入依赖不会强制要求外部存储，即默认不破坏使用者环境。
 * 生产环境必须替换为持久化存储，启动时由 {@code AuditStoreValidator} 告警提示。
 */
public class LoggingAuditStore implements AuditStore {

	private static final Logger log = LoggerFactory.getLogger("AUDIT");

	private final AuditMetrics metrics;

	public LoggingAuditStore(AuditMetrics metrics) {
		this.metrics = metrics == null ? AuditMetrics.noop() : metrics;
	}

	/**
	 * 逐条打印记录；不持久化，进程日志滚落后记录即不可追回。
	 *
	 * <p>交付指标由 AuditPipeline 统一累计（见 ADR-013），存储层不重复计数。
	 */
	@Override
	public void save(List<AuditRecord> records) {
		for (AuditRecord record : records) {
			log.info("{}", record);
		}
		// 不递增 audit.store.delivered —— 该指标由 AuditPipeline 统一负责（见 ADR-013）。
	}

	/** 存储名称，用于指标标签与存储校验。 */
	@Override
	public String getName() {
		return "logging";
	}

	/** 标记本实现为非持久化存储，不满足合规留存要求。 */
	public boolean isCompliant() {
		return false;
	}
}
