package io.github.pkyit.auditlog.spring.actuator;

import io.github.pkyit.auditlog.core.api.AuditMetrics;
import io.github.pkyit.auditlog.core.metrics.SimpleAuditMetrics;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计指标端点：{@code GET /actuator/auditLogMetrics}。
 *
 * <p>输出采集、入队、被拒绝、降级到本地日志、丢弃、投递失败与死信等计数，
 * 用于核对审计数据是否完整。
 *
 * <p>{@code audit.pipeline.dropped} 与 {@code audit.pipeline.journal.rejected}
 * 是「审计数据已经不完整」的唯一信号，必须配置告警。
 */
@Endpoint(id = "auditLogMetrics")
public class AuditLogMetricsEndpoint {

	private final AuditMetrics metrics;

	/**
	 * 创建指标端点。
	 *
	 * @param metrics 指标实现；为 {@code null} 时端点返回说明与实现类名 {@code none}
	 */
	public AuditLogMetricsEndpoint(AuditMetrics metrics) {
		this.metrics = metrics;
	}

	/**
	 * 读取审计指标快照。
	 *
	 * <p>{@link AuditMetrics} 只定义写入侧接口，不提供指标枚举，因此仅当实现为
	 * {@link SimpleAuditMetrics} 时返回完整快照；其余实现返回说明与实现类名，不抛异常。
	 *
	 * @return 指标名到值的映射，可能为空映射
	 */
	@ReadOperation
	public Map<String, Object> auditLogMetrics() {
		Map<String, Object> result = new LinkedHashMap<>();
		if (metrics instanceof SimpleAuditMetrics simple) {
			result.putAll(simple.snapshot());
		}
		else {
			result.put("note", "当前 AuditMetrics 实现不提供快照，请通过你的指标系统（如 Micrometer）查询");
			result.put("implementation", metrics == null ? "none" : metrics.getClass().getName());
		}
		return result;
	}
}
