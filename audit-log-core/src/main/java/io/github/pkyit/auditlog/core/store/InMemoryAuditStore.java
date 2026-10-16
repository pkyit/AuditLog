package io.github.pkyit.auditlog.core.store;

import io.github.pkyit.auditlog.core.api.AuditMetricNames;
import io.github.pkyit.auditlog.core.api.AuditMetrics;
import io.github.pkyit.auditlog.core.api.AuditStore;
import io.github.pkyit.auditlog.core.model.AuditRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存存储：用于测试、验收断言与故障注入。
 *
 * <p>非生产实现：进程退出即丢失全部记录，且记录集合无界增长。
 *
 * <p>故障注入（{@link #setFailing(boolean)}、{@link #setFailFirstCalls(int)}）用于验证
 * 「不丢数据」不变量：存储不可用时，框架必须把记录降级写入本地兜底日志，
 * 并在存储恢复后重放，而不是静默丢弃。
 */
public class InMemoryAuditStore implements AuditStore {

	private final List<AuditRecord> records = Collections.synchronizedList(new ArrayList<>());
	private final AtomicBoolean failing = new AtomicBoolean(false);
	private final AtomicInteger remainingInjectedFailures = new AtomicInteger(0);
	private final AtomicInteger saveCalls = new AtomicInteger(0);
	private final AuditMetrics metrics;

	/** 构造不注入故障的实例，使用 {@link AuditMetrics#noop()}。 */
	public InMemoryAuditStore() {
		this(AuditMetrics.noop());
	}

	/** 构造不注入故障的实例。 */
	public InMemoryAuditStore(AuditMetrics metrics) {
		this.metrics = metrics == null ? AuditMetrics.noop() : metrics;
	}

	/**
	 * 构造带有初始故障注入配置的实例。
	 *
	 * @param failOnSave 是否始终失败，等价于随后调用 {@code setFailing(true)}
	 * @param failFirstN 前 N 次调用失败，等价于随后调用 {@code setFailFirstCalls(n)}
	 */
	public InMemoryAuditStore(boolean failOnSave, int failFirstN, AuditMetrics metrics) {
		this(metrics);
		this.failing.set(failOnSave);
		this.remainingInjectedFailures.set(Math.max(0, failFirstN));
	}

	/**
	 * 保存整批记录；注入故障生效时本批全部失败，且不写入任何记录。
	 *
	 * <p>本方法可被多线程并发调用。
	 *
	 * @throws IllegalStateException 故障注入生效时抛出，消息包含调用序号
	 */
	@Override
	public void save(List<AuditRecord> batch) throws Exception {
		int call = saveCalls.incrementAndGet();
		if (failing.get() || remainingInjectedFailures.getAndUpdate(value -> value > 0 ? value - 1 : 0) > 0) {
			throw new IllegalStateException("注入的存储故障（第 " + call + " 次调用）");
		}
		records.addAll(batch);
		// 不递增 audit.store.delivered —— 该指标由 AuditPipeline 统一负责（见 ADR-013）。
		// 两处各计一次会使指标翻倍，指标一旦失真，"不丢数据"就无法用数据证明。
	}

	/** 存储名称，用于指标标签与存储校验。 */
	@Override
	public String getName() {
		return "in-memory";
	}

	/**
	 * 从内存中的记录回读链尾。
	 *
	 * <p>本实现遵循与真实存储相同的恢复契约，使"状态文件丢失 -> 从存储回读链尾"
	 * 这条兜底路径不必依赖数据库即可被单元测试覆盖。
	 *
	 * <p>本方法不获取实例锁，仅应在无并发写入时调用。
	 */
	@Override
	public io.github.pkyit.auditlog.core.api.ChainState recoverChainState() {
		java.util.List<AuditRecord> all = new java.util.ArrayList<>(records);
		for (int index = all.size() - 1; index >= 0; index--) {
			AuditRecord record = all.get(index);
			if (record.getRecordHash() != null) {
				return new io.github.pkyit.auditlog.core.api.ChainState(
					record.getRecordHash(), record.getSeq());
			}
		}
		return null;
	}

	/** 设置持续失败开关。 */
	public void setFailing(boolean value) {
		failing.set(value);
	}

	/**
	 * 注入前 N 次调用失败。
	 *
	 * <p>计数在每次 save 调用时递减，不会被历史失败永久耗尽。
	 */
	public void setFailFirstCalls(int count) {
		remainingInjectedFailures.set(Math.max(0, count));
	}

	/** 当前是否处于持续失败状态。 */
	public boolean isFailing() {
		return failing.get();
	}

	/** 当前记录条数的快照，调用方修改返回列表不影响内部记录。 */
	public List<AuditRecord> getRecords() {
		synchronized (records) {
			return List.copyOf(records);
		}
	}

	/** 当前记录条数。 */
	public int size() {
		return records.size();
	}

	/** save 调用总次数，包含因注入故障而失败的调用。 */
	public int saveCallCount() {
		return saveCalls.get();
	}

	/** 清空记录；故障注入配置与调用次数不受影响。 */
	public void clear() {
		records.clear();
	}
}
