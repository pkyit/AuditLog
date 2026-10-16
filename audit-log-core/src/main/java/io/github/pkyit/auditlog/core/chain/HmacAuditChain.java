package io.github.pkyit.auditlog.core.chain;

import io.github.pkyit.auditlog.core.api.AuditChain;
import io.github.pkyit.auditlog.core.model.AuditRecord;

import java.util.List;

/**
 * 带 HMAC-SHA256 签名的哈希链。
 *
 * <p>与 {@link Sha256AuditChain} 的关键差别：链哈希与签名都使用仅应用侧持有的密钥。
 * 存储侧（数据库／文件系统／MQ）即使被完全攻陷，攻击者也无法伪造可通过校验的链，
 * 因为其不持有密钥。这是"历史不可篡改"不变量在生产环境的落法。
 *
 * <p>密钥不得写入代码或镜像，应从密钥管理服务／环境变量注入，并支持轮换；
 * 轮换后校验旧记录仍需旧密钥。
 *
 * <p>链头与计数均在实例内维护，所有访问方法均以 {@code synchronized} 串行化。
 */
public class HmacAuditChain implements AuditChain {

	private static final String GENESIS = "GENESIS";

	private final String secret;
	private String head = GENESIS;
	private long linked;

	/**
	 * 以签名密钥创建链。
	 *
	 * @param secret HMAC 密钥，不可为 {@code null} 或空串
	 * @throws IllegalArgumentException {@code secret} 为 {@code null} 或空串
	 */
	public HmacAuditChain(String secret) {
		if (secret == null || secret.isEmpty()) {
			throw new IllegalArgumentException("HmacAuditChain 需要非空密钥");
		}
		this.secret = secret;
	}

	/**
	 * 把记录接入链尾，返回带链信息（prevHash、recordHash、signature）的副本。
	 *
	 * <p>{@code recordHash} 为 {@code prevHash + '|' + canonicalContent()} 的 HMAC；
	 * {@code signature} 为 {@code recordHash} 的 HMAC，二者均使用构造时注入的密钥。
	 * 入参记录本身不被修改；本方法以 {@code synchronized} 保证并发调用时链的线性推进。
	 *
	 * @param record 待链接的记录；其 {@code canonicalContent()} 参与哈希计算。
	 *               链是有状态的串行结构，{@code seq} 必须大于链上已有记录的 {@code seq}，否则链会倒退
	 * @return 已填充链信息的记录副本
	 */
	@Override
	public synchronized AuditRecord link(AuditRecord record) {
		String previous = head;
		String hash = Hasher.hmacSha256(secret, previous + '|' + record.canonicalContent());
		String signature = Hasher.hmacSha256(secret, hash);
		this.head = hash;
		this.linked++;
		return record.withChain(previous, hash, signature);
	}

	/**
	 * 从持久化状态恢复链头。
	 *
	 * <p>与 {@link Sha256AuditChain#restoreHead(String)} 同理：链头只存在于内存，
	 * 重启后必须从存储中最后一条记录的 hash 恢复，否则新记录的 {@code prevHash}
	 * 无法与存储中最后一条衔接，跨重启校验必然报"被篡改"。
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
	 * 返回当前链头哈希。
	 *
	 * @return 最后一条已链接记录的 recordHash；尚未链接任何记录时为 {@code GENESIS}
	 */
	@Override
	public synchronized String currentHead() {
		return head;
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
	 * 逐条校验链的完整性与签名。
	 *
	 * <p>校验内容：相邻记录的 {@code prevHash} 必须等于前一条的 {@code recordHash}；
	 * {@code recordHash} 必须与用当前密钥按 {@code prevHash + '|' + canonicalContent()}
	 * 重算的结果一致；{@code signature} 必须与 {@code recordHash} 的 HMAC 一致。
	 * 哈希与签名的比较使用恒定时间比较，避免比较耗时泄露签名信息。
	 *
	 * <p>{@code recordHash} 为 {@code null} 的记录（链功能开启之前产生的历史记录）跳过校验，
	 * 但仍计入通过条数；此类记录不参与后续 {@code prevHash} 的衔接判断。
	 *
	 * <p>密钥轮换后，用新密钥校验旧记录会在签名比对处失败。
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
				// 链功能开启前产生的历史记录不含链信息，跳过校验但不计为失败
				verified++;
				continue;
			}
			if (previous != null && !previous.equals(record.getPrevHash())) {
				return ChainVerification.broken(verified, record.getSeq(), "prevHash 与前一条 recordHash 不一致");
			}
			String expectedHash = Hasher.hmacSha256(secret, record.getPrevHash() + '|' + record.canonicalContent());
			if (!Hasher.constantTimeEquals(expectedHash, record.getRecordHash())) {
				return ChainVerification.broken(verified, record.getSeq(), "recordHash 重算不一致（内容可能被篡改）");
			}
			String expectedSignature = Hasher.hmacSha256(secret, expectedHash);
			if (!Hasher.constantTimeEquals(expectedSignature, record.getSignature())) {
				return ChainVerification.broken(verified, record.getSeq(), "HMAC 签名不匹配（缺少密钥或记录被伪造）");
			}
			previous = record.getRecordHash();
			verified++;
		}
		return ChainVerification.ok(verified);
	}
}
