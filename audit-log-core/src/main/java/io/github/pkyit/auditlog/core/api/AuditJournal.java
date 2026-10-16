package io.github.pkyit.auditlog.core.api;

import io.github.pkyit.auditlog.core.model.AuditRecord;

import java.util.List;

/**
 * 本地兜底日志（WAL / sidecar journal）SPI。
 *
 * <p>它存在的唯一目的：当业务通道（队列 + 存储）不可用时，把审计记录落到本地磁盘，
 * 使「不丢数据」在存储故障、网络分区、队列打满等情况下依然成立。
 *
 * <p>实现约定：
 * <ul>
 *   <li>追加写、每行一条、格式自包含（可通过 {@link AuditCodec} 解析）；</li>
 *   <li>{@link #append} MUST NOT 抛异常：失败时返回 {@code false}，由框架计数并告警；
 *       抛出异常会使队列满这一原始故障无法上报；</li>
 *   <li>{@link #readPending} 支持分页，避免重放时一次性把待重放记录读入内存；</li>
 *   <li>{@link #checkpoint} 记录重放进度，避免重复重放（幂等仍由存储保证）。</li>
 * </ul>
 *
 * <p>实现须可被多线程并发调用：{@code append} 来自投递线程，{@code readPending} 与
 * {@code checkpoint} 来自重放线程。
 */
public interface AuditJournal {

	/**
	 * 追加一条记录。
	 *
	 * @return {@code true} 成功落盘；{@code false} 被拒绝（框架计入 {@code journal.rejected} 并告警）
	 */
	boolean append(AuditRecord record);

	/** 追加到死信区（重试耗尽后）；默认实现与普通追加等价。 */
	default boolean appendDeadLetter(AuditRecord record, String reason) {
		return append(record);
	}

	/**
	 * 读取待重放记录。
	 *
	 * @param maxCount 单次最大返回条数
	 * @return 待重放记录（按 {@code seq} 升序）
	 */
	List<AuditRecord> readPending(int maxCount);

	/** 记录已成功重放到的序号。 */
	void checkpoint(long seq);

	/** 当前待重放数量（近似值，用于指标与健康检查）。 */
	long pendingCount();

	/** 是否可用；返回 {@code false} 时框架停止向其追加并计入指标。 */
	default boolean isHealthy() {
		return true;
	}
}
