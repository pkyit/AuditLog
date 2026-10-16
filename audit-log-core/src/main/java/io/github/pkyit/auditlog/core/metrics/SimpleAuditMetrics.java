package io.github.pkyit.auditlog.core.metrics;

import io.github.pkyit.auditlog.core.api.AuditMetrics;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 基于 JDK 并发容器的默认指标实现，无外部依赖。
 *
 * <p>计数走 {@link LongAdder}：写入分散到多个 cell，读取时才聚合，避免高并发计数在单点上竞争；
 * gauge 使用 {@link AtomicLong} 保存瞬时值。名称到容器的映射只增不删，因此指标名必须来自
 * 有界的固定集合，否则条目会随指标名累积而无法回收。
 *
 * <p>所有方法线程安全，且不抛异常、不阻塞：名称为 {@code null} 或取值无意义时静默忽略，
 * 指标写入失败不得影响业务链路。可通过 {@link #snapshot()} 导出全部指标，供 Actuator 端点
 * 与验收断言使用。
 */
public class SimpleAuditMetrics implements AuditMetrics {

	private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
	private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

	/** 计数器 +1，等价于 {@link #increment(String, long) increment(name, 1L)}。 */
	@Override
	public void increment(String name) {
		increment(name, 1L);
	}

	/**
	 * 累加计数器。
	 *
	 * <p>{@code name} 为 {@code null} 或 {@code delta} 为 0 时直接返回，不创建对应条目；
	 * {@code delta} 允许为负，因此计数器取值不保证非负。
	 */
	@Override
	public void increment(String name, long delta) {
		if (name == null || delta == 0L) {
			return;
		}
		counters.computeIfAbsent(name, key -> new LongAdder()).add(delta);
	}

	/**
	 * 设置 gauge 的当前值。
	 *
	 * <p>{@code name} 为 {@code null} 时直接返回。同一名称被并发写入时结果取决于执行顺序，
	 * 不保证保留某一次调用的值。
	 */
	@Override
	public void gauge(String name, long value) {
		if (name == null) {
			return;
		}
		gauges.computeIfAbsent(name, key -> new AtomicLong()).set(value);
	}

	/**
	 * 记录一次耗时样本（毫秒）。
	 *
	 * <p>一次调用会在快照中额外产生三个指标名：{@code name.sum}（累计耗时）、{@code name.count}
	 * （样本数）、{@code name.max}（历史最大耗时）。{@code name} 为 {@code null} 时直接返回，
	 * 不产生任何指标。
	 */
	@Override
	public void recordDuration(String name, long millis) {
		if (name == null) {
			return;
		}
		String sumName = name + ".sum";
		String countName = name + ".count";
		String maxName = name + ".max";
		counters.computeIfAbsent(sumName, key -> new LongAdder()).add(millis);
		counters.computeIfAbsent(countName, key -> new LongAdder()).add(1L);
		// max 必须单调不减：用 accumulateAndGet 原子取大，set 会在并发下丢掉更大的样本。
		gauges.computeIfAbsent(maxName, key -> new AtomicLong())
			.accumulateAndGet(millis, Math::max);
	}

	/**
	 * 读取计数器当前值。
	 *
	 * <p>名称从未写入时返回 0，与存在但和为 0 的情况不可区分；读取不构成一致性快照，
	 * 并发写入期间返回值可能落后于实际值。
	 */
	public long counter(String name) {
		LongAdder adder = counters.get(name);
		return adder == null ? 0L : adder.sum();
	}

	/**
	 * 读取 gauge 当前值。
	 *
	 * <p>名称从未写入时返回 -1；gauge 的合法取值包含 0，因此用 -1 专门表示未设置。
	 */
	public long gaugeValue(String name) {
		AtomicLong value = gauges.get(name);
		return value == null ? -1L : value.get();
	}

	/**
	 * 导出全部指标快照。
	 *
	 * <p>键按字典序排序，便于断言与人工核对；counter 与 gauge 同名时 gauge 覆盖 counter。
	 * 返回的 Map 不可变，但内容不是时间点一致视图：并发写入期间各指标可能取自不同瞬间。
	 */
	public Map<String, Long> snapshot() {
		Map<String, Long> snapshot = new TreeMap<>();
		counters.forEach((key, value) -> snapshot.put(key, value.sum()));
		gauges.forEach((key, value) -> snapshot.put(key, value.get()));
		return Collections.unmodifiableMap(snapshot);
	}
}
