package io.github.pkyit.auditlog.core.chain;

import io.github.pkyit.auditlog.core.api.AuditChain;
import io.github.pkyit.auditlog.core.model.ActionType;
import io.github.pkyit.auditlog.core.model.AuditRecord;
import io.github.pkyit.auditlog.core.model.Outcome;
import io.github.pkyit.auditlog.core.model.PrincipalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防篡改哈希链测试（不变量 I4）。
 *
 * <p>覆盖的能力边界：
 * <ul>
 *   <li>完好链校验通过；</li>
 *   <li>篡改任意一条都被发现，且定位到具体 seq，而不是仅报告链损坏；</li>
 *   <li>删除记录导致 prevHash 链断裂并被发现；</li>
 *   <li>HMAC 链在没有密钥或密钥错误时无法伪造。</li>
 * </ul>
 */
class AuditChainTest {

	private AuditRecord record(long seq, String operation) {
		return new AuditRecord.Builder()
			.id("id-" + seq)
			.seq(seq)
			.eventTime(Instant.parse("2024-01-01T00:00:00Z"))
			.principal("user-1", "user-1", PrincipalType.AUTHENTICATED)
			.operation(operation)
			.actionType(ActionType.MUTATE)
			.resource("user", "42")
			.http("POST", "/api/user/42", null)
			.outcome(Outcome.SUCCESS)
			.httpStatus(200)
			.durationMillis(10L)
			.build();
	}

	private List<AuditRecord> linkedChain(AuditChain chain, int count) {
		List<AuditRecord> records = new ArrayList<>(count);
		for (int index = 1; index <= count; index++) {
			records.add(chain.link(record(index, "操作-" + index)));
		}
		return records;
	}

	@Test
	@DisplayName("SHA-256 链：完好链校验通过")
	void sha256ChainValidatesCleanChain() {
		AuditChain chain = new Sha256AuditChain();
		List<AuditRecord> records = linkedChain(chain, 5);
		AuditChain.ChainVerification verification = chain.verify(records);
		assertTrue(verification.valid(), "完好链应校验通过，原因：" + verification.reason());
		assertEquals(5, verification.verifiedCount());
	}

	@Test
	@DisplayName("SHA-256 链：篡改内容被检出并定位到该条")
	void sha256ChainDetectsTamperedContent() {
		AuditChain chain = new Sha256AuditChain();
		List<AuditRecord> records = linkedChain(chain, 5);

		// 攻击者直接改写第 3 条的操作名
		List<AuditRecord> tampered = new ArrayList<>(records);
		tampered.set(2, records.get(2).toBuilder().operation("被篡改的操作").build());

		AuditChain.ChainVerification verification = chain.verify(tampered);
		assertFalse(verification.valid(), "内容被篡改后校验必须失败");
		assertEquals(3L, verification.brokenAtSeq(), "应定位到被篡改的那一条");
	}

	@Test
	@DisplayName("SHA-256 链：删除中间记录导致链断裂并被发现")
	void sha256ChainDetectsRemoval() {
		AuditChain chain = new Sha256AuditChain();
		List<AuditRecord> records = linkedChain(chain, 5);
		List<AuditRecord> removed = new ArrayList<>(records);
		removed.remove(2);

		AuditChain.ChainVerification verification = chain.verify(removed);
		assertFalse(verification.valid(), "删除记录后 prevHash 链必然断裂");
	}

	@Test
	@DisplayName("SHA-256 链：链头随记录变化，可作为锚点")
	void chainHeadChanges() {
		AuditChain chain = new Sha256AuditChain();
		String before = chain.currentHead();
		chain.link(record(1, "操作"));
		String after = chain.currentHead();
		assertNotEquals(before, after, "链头应随链接而变化，供外部锚定");
		assertNotNull(after);
	}

	@Test
	@DisplayName("未启用时的空实现：不链接、验证恒通过")
	void noopChainDoesNothing() {
		AuditChain chain = Sha256AuditChain.noop();
		assertFalse(chain.isEnabled());
		AuditRecord linked = chain.link(record(1, "操作"));
		assertEquals(null, linked.getRecordHash(), "空实现不应写入链字段");
		assertTrue(chain.verify(List.of(linked)).valid());
	}

	@Test
	@DisplayName("HMAC 链：完好链校验通过且带签名")
	void hmacChainValidatesCleanChain() {
		AuditChain chain = new HmacAuditChain("test-secret-key");
		List<AuditRecord> records = linkedChain(chain, 4);
		assertNotNull(records.get(0).getSignature(), "HMAC 链应写入签名");
		assertTrue(chain.verify(records).valid());
	}

	@Test
	@DisplayName("HMAC 链：密钥不同则无法通过校验（伪造被拒）")
	void hmacChainRejectsWrongSecret() {
		AuditChain honest = new HmacAuditChain("correct-secret");
		List<AuditRecord> records = linkedChain(honest, 3);

		// 攻击者没有密钥，用错误密钥重新生成整条链
		AuditChain attacker = new HmacAuditChain("attacker-guess");
		List<AuditRecord> forged = new ArrayList<>();
		for (AuditRecord original : records) {
			forged.add(attacker.link(original.toBuilder().build()));
		}

		AuditChain.ChainVerification verification = honest.verify(forged);
		assertFalse(verification.valid(), "错误密钥伪造的链必须校验失败");
	}

	@Test
	@DisplayName("HMAC 链：篡改内容被检出并定位")
	void hmacChainDetectsTamperedContent() {
		AuditChain chain = new HmacAuditChain("test-secret-key");
		List<AuditRecord> records = linkedChain(chain, 4);
		List<AuditRecord> tampered = new ArrayList<>(records);
		tampered.set(1, records.get(1).toBuilder().httpStatus(500).outcome(Outcome.FAILURE).build());

		AuditChain.ChainVerification verification = chain.verify(tampered);
		assertFalse(verification.valid());
		assertEquals(2L, verification.brokenAtSeq());
	}

	@Test
	@DisplayName("空密钥应被拒绝（避免误以为启用了签名）")
	void hmacChainRejectsEmptySecret() {
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> new HmacAuditChain(""));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> new HmacAuditChain(null));
	}

	@Test
	@DisplayName("规范化内容稳定：同一记录在不同时刻产生相同摘要输入")
	void canonicalContentIsStable() {
		AuditRecord first = record(1, "操作").withChain("PREV", "HASH", "SIG");
		// 链字段不参与摘要计算，因此 withChain 前后 canonicalContent 必须一致
		assertEquals(record(1, "操作").canonicalContent(), first.canonicalContent(),
			"链字段不得参与摘要，否则无法重算校验");
	}

	@Test
	@DisplayName("离线可校验：记录经 JSON 编解码往返后，哈希仍可被独立重算（回归）")
	void canonicalContentSurvivesCodecRoundTrip() {
		AuditChain chain = new Sha256AuditChain();
		List<AuditRecord> records = linkedChain(chain, 3);

		io.github.pkyit.auditlog.core.serialize.JsonLinesAuditCodec codec =
			new io.github.pkyit.auditlog.core.serialize.JsonLinesAuditCodec();

		// 模拟"审计文件被第三方读取并独立校验"：编码 -> 落盘 -> 解码 -> 重算哈希
		List<AuditRecord> decoded = new ArrayList<>();
		for (AuditRecord original : records) {
			AuditRecord roundTripped = codec.decode(codec.encode(original));
			assertNotNull(roundTripped, "记录必须能被解码");
			decoded.add(roundTripped);
		}

		// 关键断言 1：往返后规范化内容必须与原始完全一致。
		// null 字段必须规范化为空串：若编码侧写入字面量 "null" 而解码侧得到空串，
		// 从审计文件重算的哈希将永远无法匹配，离线复核的防篡改校验随之失效。
		for (int index = 0; index < records.size(); index++) {
			assertEquals(records.get(index).canonicalContent(), decoded.get(index).canonicalContent(),
				"第 " + (index + 1) + " 条记录往返后规范化内容发生了变化，链将无法离线校验");
		}

		// 关键断言 2：用解码后的记录重算链，必须依然校验通过
		AuditChain.ChainVerification verification = chain.verify(decoded);
		assertTrue(verification.valid(),
			"从持久化内容重算的链必须校验通过，失败原因：" + verification.reason());
		assertEquals(3, verification.verifiedCount());

		// 关键断言 3：逐条独立重算哈希，必须与落盘值一致。
		// 外部离线验证审计历史的前提，是能按同一公式复现哈希；
		// 仅调用框架自身的 verify() 属于自证，不足以证明记录可离线校验。
		for (AuditRecord roundTripped : decoded) {
			String expected = io.github.pkyit.auditlog.core.chain.Hasher
				.sha256(roundTripped.getPrevHash() + '|' + roundTripped.canonicalContent());
			assertEquals(roundTripped.getRecordHash(), expected,
				"seq=" + roundTripped.getSeq() + " 的 recordHash 与其规范化内容不自洽，"
					+ "外部无法离线校验该记录");
		}
	}

	@Test
	@DisplayName("链哈希自洽：recordHash 必须等于 sha256(prevHash + '|' + canonicalContent)")
	void recordHashIsSelfConsistent() {
		AuditChain chain = new Sha256AuditChain();
		List<AuditRecord> records = linkedChain(chain, 3);
		for (AuditRecord record : records) {
			String expected = Hasher.sha256(record.getPrevHash() + '|' + record.canonicalContent());
			assertEquals(expected, record.getRecordHash(),
				"seq=" + record.getSeq() + " 的链哈希与规范化内容不一致");
		}
		// 同时确认 prevHash 真正串成一条链
		assertEquals("GENESIS", records.get(0).getPrevHash());
		for (int index = 1; index < records.size(); index++) {
			assertEquals(records.get(index - 1).getRecordHash(), records.get(index).getPrevHash(),
				"第 " + index + " 条的 prevHash 应指向前一条的 recordHash");
		}
	}

	@Test
	@DisplayName("规范化内容对 null 字段稳定：null 一律表示为空串而非字面量 null")
	void canonicalContentNormalizesNulls() {
		AuditRecord withNulls = new AuditRecord.Builder()
			.id("id-null-test")
			.seq(7L)
			.eventTime(null)
			.principal(null, null, null)
			.tenantId(null)
			.traceId(null)
			.operation(null)
			.actionType(null)
			.resource(null, null)
			.http(null, null, null)
			.outcome(null)
			.httpStatus(null)
			.businessResult(new io.github.pkyit.auditlog.core.model.BusinessResult(null, null, true))
			.error(null)
			.durationMillis(5L)
			.build();

		String canonical = withNulls.canonicalContent();
		assertFalse(canonical.contains("=null"), "规范化内容不得出现字面量 null，实际：" + canonical);
		assertTrue(canonical.contains("|bizCode=|"), "空业务码应表示为空串，实际：" + canonical);
		assertTrue(canonical.contains("|httpStatus=|"), "空状态码应表示为空串，实际：" + canonical);
	}

	@Test
	@DisplayName("HMAC 链：编解码往返后签名仍可校验")
	void hmacChainSurvivesCodecRoundTrip() {
		AuditChain chain = new HmacAuditChain("round-trip-secret");
		List<AuditRecord> records = linkedChain(chain, 3);
		io.github.pkyit.auditlog.core.serialize.JsonLinesAuditCodec codec =
			new io.github.pkyit.auditlog.core.serialize.JsonLinesAuditCodec();

		List<AuditRecord> decoded = new ArrayList<>();
		for (AuditRecord original : records) {
			decoded.add(codec.decode(codec.encode(original)));
		}
		AuditChain.ChainVerification verification = chain.verify(decoded);
		assertTrue(verification.valid(),
			"HMAC 链在持久化往返后必须仍可校验，失败原因：" + verification.reason());
	}
}
