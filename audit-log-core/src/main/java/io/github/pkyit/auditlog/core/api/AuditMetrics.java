package io.github.pkyit.auditlog.core.api;

/**
 * 指标 SPI。
 *
 * <p>审计链路的任何一次丢弃或降级都必须可被观测，否则「不丢数据」无法验证。
 * 所有「可能丢数据」的路径都必须有对应计数器，指标名见 {@link AuditMetricNames}。
 *
 * <p>实现 MUST NOT 抛异常，MUST NOT 阻塞：指标属于旁路，不得因其自身故障影响采集线程。
 */
public interface AuditMetrics {

	/** 计数器 +1。 */
	void increment(String name);

	/** 计数器 +delta（{@code delta} 可为负）。 */
	void increment(String name, long delta);

	/** 设置瞬时值（队列深度、WAL 积压等）。 */
	void gauge(String name, long value);

	/** 记录耗时（毫秒）。 */
	void recordDuration(String name, long millis);

	/** 空实现：不记录任何指标，供未接入监控的部署使用。 */
	static AuditMetrics noop() {
		return new AuditMetrics() {
			@Override
			public void increment(String name) {
			}

			@Override
			public void increment(String name, long delta) {
			}

			@Override
			public void gauge(String name, long value) {
			}

			@Override
			public void recordDuration(String name, long millis) {
			}
		};
	}
}
