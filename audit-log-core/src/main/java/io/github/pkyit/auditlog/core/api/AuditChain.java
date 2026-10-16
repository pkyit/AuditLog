package io.github.pkyit.auditlog.core.api;

import io.github.pkyit.auditlog.core.model.AuditRecord;

/**
 * 防篡改哈希链 SPI。
 *
 * <p>不变量 I4：审计历史必须可验证未被篡改。做法是把每条记录用
 * {@code prevHash} 串成链，任何一条被改动都会导致其后所有记录的哈希不匹配。
 *
 * <p><b>并发约束（必须遵守）</b>：链是有状态的串行结构，
 * 调用 {@link #link} 的顺序直接决定 {@code prevHash} 的取值。
 * 因此调用方必须保证：要么投递线程为单个；要么在入队时已分配好 {@code seq}，
 * 由单线程聚合器按 {@code seq} 顺序调用本接口；并发调用会使链出现分叉。
 */
public interface AuditChain {

	/**
	 * 为记录计算链信息。
	 *
	 * <p>实现 MUST 线程安全（内部同步），但调用顺序决定链的正确性。
	 *
	 * @param record 待链接记录；其 {@code seq} 必须大于链上已有记录的 {@code seq}，否则链会倒退
	 * @return 带链字段的新记录；入参不被修改
	 */
	AuditRecord link(AuditRecord record);

	/** 当前链头哈希（用于外部锚定 / 校验起点）；空链时返回 {@code null}。 */
	String currentHead();

	/** 是否启用（未启用时返回 {@code false}，框架跳过链接步骤）。 */
	default boolean isEnabled() {
		return true;
	}

	/**
	 * 校验记录链的完整性。
	 *
	 * <p>实现应逐条重算哈希并比对 {@code prevHash} 是否与前一条的 {@code recordHash} 一致，
	 * 返回第一个不一致的位置，使「哪一条被改过」可定位，而不只是「链坏了」。
	 *
	 * @param records 按 {@code seq} 升序排列的记录
	 * @return 校验结果
	 */
	ChainVerification verify(java.util.List<AuditRecord> records);

	/**
	 * 链校验结果。
	 *
	 * @param valid         是否完整
	 * @param verifiedCount 校验通过的条数
	 * @param brokenAtSeq   第一个校验失败的 {@code seq}，{@code -1} 表示无
	 * @param reason        失败原因
	 */
	record ChainVerification(boolean valid, int verifiedCount, long brokenAtSeq, String reason) {

		/** 全链校验通过。 */
		public static ChainVerification ok(int verifiedCount) {
			return new ChainVerification(true, verifiedCount, -1L, null);
		}

		/**
		 * 在指定 {@code seq} 处校验失败。
		 *
		 * @param verifiedCount 断点之前校验通过的条数
		 * @param seq           第一个校验失败的 {@code seq}
		 * @param reason        失败原因；跨重启断链等无法定位单条记录的情形在此说明
		 */
		public static ChainVerification broken(int verifiedCount, long seq, String reason) {
			return new ChainVerification(false, verifiedCount, seq, reason);
		}
	}
}
