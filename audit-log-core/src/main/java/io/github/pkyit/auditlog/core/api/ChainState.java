package io.github.pkyit.auditlog.core.api;

/**
 * 哈希链的持久化状态：链头哈希与已完成投递的最大 seq。
 *
 * <p>链头必须跨进程重启保留：链的连续性不仅依赖记录本身，还依赖上一条记录的
 * {@code recordHash}。若重启后链头从 {@code GENESIS} 重新开始，新记录的 {@code prevHash}
 * 与存储中最后一条记录的 {@code recordHash} 无法衔接，
 * 任何跨重启的链校验都会在重启边界报「被篡改」，而记录实际未被改动。
 *
 * <p>{@code lastDeliveredSeq} 用于崩溃恢复：若不知道哪些 {@code seq} 已经落库，
 * 重放会把已投递记录再链一次，使内存链头前进到从未落库的哈希上，
 * 此后所有实时记录的 {@code prevHash} 都指向存储中不存在的值，
 * 链永久断裂且没有任何指标暴露。
 *
 * @param head             当前链头（上一条已投递记录的 recordHash）；空链为 {@code null}
 * @param lastDeliveredSeq 已成功写入存储的最大 seq；无记录为 {@code 0}
 */
public record ChainState(String head, long lastDeliveredSeq) {

	/** 空链状态。 */
	public static ChainState pending() {
		return new ChainState(null, 0L);
	}

	/** 是否为冷启动（没有任何已投递记录）；为 {@code true} 时链从 {@code GENESIS} 开始。 */
	public boolean isPending() {
		return head == null && lastDeliveredSeq <= 0L;
	}
}
