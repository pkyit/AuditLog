package io.github.pkyit.auditlog.core.chain;

import io.github.pkyit.auditlog.core.api.AuditChain;
import io.github.pkyit.auditlog.core.model.AuditRecord;

import java.util.List;

/**
 * 无密钥的 SHA-256 哈希链。
 *
 * <p>能力边界：只能发现意外损坏与无密钥篡改（改动任意一条记录都必须重算其后的整条链），
 * 无法抵御掌握存储写权限的攻击者 —— 对方改完记录后可重算整条链。
 * 需要抵御此类攻击时应改用 {@link HmacAuditChain}（密钥不落在存储侧），
 * 或把 {@link #currentHead()} 定期锚定到外部不可变存储 / 可信时间戳服务。
 *
 * <p>链头与计数均在实例内维护，所有访问方法均以 {@code synchronized} 串行化。
 */
public class Sha256AuditChain implements AuditChain {

	private static final String GENESIS = "GENESIS";

	private String head = GENESIS;
	private long linked;

	/**
	 * 把记录接入链尾，返回带链信息（prevHash、recordHash）的副本。
	 *
	 * <p>入参记录本身不被修改。本方法以 {@code synchronized} 保证并发调用时链的线性推进。
	 *
	 * @param record 待链接的记录；其 {@code canonicalContent()} 参与哈希计算。
	 *               链是有状态的串行结构，{@code seq} 必须大于链上已有记录的 {@code seq}，否则链会倒退
	 * @return 已填充链信息的记录副本，{@code signature} 恒为 {@code null}
	 */
	@Override
	public synchronized AuditRecord link(AuditRecord record) {
		String previous = head;
		String hash = Hasher.sha256(previous + '|' + record.canonicalContent());
		this.head = hash;
		this.linked++;
		return record.withChain(previous, hash, null);
	}

	/**
	 * 返回当前链头哈希。
	 *
	 * @return 最后一条已链接记录的 recordHash；尚未链接任何记录时为 {@code GENESIS}
	 */
	@Override
	public synchronized String currentHead() {
		return head;
	}

	/**
	 * 从持久化状态恢复链头。
	 *
	 * <p>{@code head} 仅存在于内存。进程重启后若从 {@code GENESIS} 重新起链，新记录的
	 * {@code prevHash} 与存储中最后一条记录的 {@code recordHash} 无法衔接，任何跨重启的链校验
	 * 都会在重启边界报"被篡改"，而记录实际未被篡改。因此重启后必须先恢复链头再写入。
	 *
	 * <p>恢复失败（状态文件缺失且存储读不回）时不得静默忽略：本方法返回 {@code false}，
	 * 由调用方决定告警或拒绝启动。
	 *
	 * @param previousHead 已投递的最后一条记录的 recordHash；{@code null} 或空白表示无历史
	 * @return 是否成功恢复；{@code false} 表示链头未变（仍为原值），调用方应告警
	 */
	public synchronized boolean restoreHead(String previousHead) {
		if (previousHead == null || previousHead.isBlank()) {
			return false;
		}
		this.head = previousHead;
		return true;
	}

	/**
	 * 返回本实例已链接的记录数。
	 *
	 * <p>该计数为内存态，不随 {@link #restoreHead(String)} 恢复。
	 *
	 * @return 自实例创建以来成功调用 {@link #link(AuditRecord)} 的次数
	 */
	public synchronized long linkedCount() {
		return linked;
	}

	/**
	 * 逐条校验链的完整性。
	 *
	 * <p>校验规则：相邻记录的 {@code prevHash} 必须等于前一条的 {@code recordHash}；
	 * 且每条记录的 {@code recordHash} 必须等于按 {@code prevHash + '|' + canonicalContent()}
	 * 重算的结果。
	 *
	 * <p>{@code recordHash} 为 {@code null} 的记录（链功能开启之前产生的历史记录）跳过哈希校验，
	 * 但仍计入通过条数；此类记录不参与后续 {@code prevHash} 的衔接判断。
	 *
	 * @param records 待校验记录，必须按链顺序排列
	 * @return 校验结果；入参为 {@code null} 或空时返回通过且计数为 0，发现不一致时在首个断点处返回
	 */
	@Override
	public ChainVerification verify(List<AuditRecord> records) {
		if (records == null || records.isEmpty()) {
			return ChainVerification.ok(0);
		}
		String previous = null;
		int verified = 0;
		for (AuditRecord record : records) {
			if (record.getRecordHash() == null) {
				// 链功能开启前产生的历史记录不含链信息，跳过哈希校验但不计为失败
				verified++;
				continue;
			}
			if (previous != null && !previous.equals(record.getPrevHash())) {
				return ChainVerification.broken(verified, record.getSeq(), "prevHash 与前一条 recordHash 不一致");
			}
			String expected = Hasher.sha256(record.getPrevHash() + '|' + record.canonicalContent());
			if (!expected.equals(record.getRecordHash())) {
				return ChainVerification.broken(verified, record.getSeq(), "recordHash 重算不一致（内容可能被篡改）");
			}
			previous = record.getRecordHash();
			verified++;
		}
		return ChainVerification.ok(verified);
	}

	/**
	 * 返回链功能关闭时使用的空实现。
	 *
	 * <p>{@link AuditChain#link(AuditRecord)} 原样返回入参，{@link AuditChain#currentHead()}
	 * 恒为 {@code null}，{@link AuditChain#isEnabled()} 恒为 {@code false}，
	 * {@link AuditChain#verify(List)} 视为全部通过。
	 *
	 * @return 无副作用的 {@link AuditChain} 实现
	 */
	public static AuditChain noop() {
		return new AuditChain() {
			@Override
			public AuditRecord link(AuditRecord record) {
				return record;
			}

			@Override
			public String currentHead() {
				return null;
			}

			@Override
			public boolean isEnabled() {
				return false;
			}

			@Override
			public ChainVerification verify(List<AuditRecord> records) {
				return ChainVerification.ok(records == null ? 0 : records.size());
			}
		};
	}
}
