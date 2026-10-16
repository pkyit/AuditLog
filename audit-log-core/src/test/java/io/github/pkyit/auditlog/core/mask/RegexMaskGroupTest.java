package io.github.pkyit.auditlog.core.mask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RegexMaskGroup} 的语义等价性测试。
 *
 * <p>该类以合并预筛替代逐条扫描（避免每个值重复执行全部正则）。预筛的失效方式是
 * "静默漏掉本应脱敏的内容"，因此这里以参考实现对照锁死等价性：对同一输入，
 * 逐条扫描与合并预筛的结果必须完全一致。
 */
class RegexMaskGroupTest {

	private final List<RegexMaskRule> rules = List.of(
		RegexMaskRule.bearerToken(), RegexMaskRule.idCard(), RegexMaskRule.bankCard(),
		RegexMaskRule.mobile(), RegexMaskRule.email());

	/**
	 * 参考实现：按规则列表顺序逐条完整扫描文本，作为等价性断言的基准。
	 *
	 * <p>规则按顺序串行作用：后一条规则扫描的是前一条替换后的文本。
	 */
	private String referenceMask(String text) {
		String result = text;
		for (RegexMaskRule rule : rules) {
			Matcher matcher = rule.pattern().matcher(result);
			StringBuilder builder = new StringBuilder();
			boolean found = false;
			while (matcher.find()) {
				found = true;
				matcher.appendReplacement(builder, Matcher.quoteReplacement(mask(matcher.group(), rule)));
			}
			if (found) {
				matcher.appendTail(builder);
				result = builder.toString();
			}
		}
		return result;
	}

	private String mask(String value, RegexMaskRule rule) {
		int prefix = Math.max(0, Math.min(rule.keepPrefix(), value.length()));
		int suffix = Math.max(0, Math.min(rule.keepSuffix(), value.length() - prefix));
		if (rule.maskType() == MaskType.FULL) {
			return "******";
		}
		StringBuilder builder = new StringBuilder();
		builder.append(value, 0, prefix);
		int maskedLength = value.length() - prefix - suffix;
		builder.append("*".repeat(Math.max(1, Math.min(maskedLength, 32))));
		if (suffix > 0) {
			builder.append(value, value.length() - suffix, value.length());
		}
		return builder.toString();
	}

	private String optimizedMask(String text) {
		return RegexMaskGroup.of(rules).mask(text, this::mask);
	}

	@Test
	@DisplayName("等价性：多种输入下，合并预筛与逐条扫描结果完全一致")
	void equivalentToReferenceImplementation() {
		String[] samples = {
			// 无敏感内容（最常见路径，应被预筛一次拦下）
			"payload=" + "p".repeat(180),
			"hello world",
			"",
			"orderId=ORD-2024-000001",
			// 手机号
			"contact 13812345678 now",
			"mobile=13800138000&name=zhangsan",
			// 身份证
			"id 110101199003071234 end",
			// 银行卡
			"card 6222021234567890123 ok",
			// 邮箱
			"mail zhangsan@example.com",
			// Bearer 令牌
			"Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc.def",
			// 多种同时出现
			"tel 13900000001 id 110101199003071234 card 6222021234567890123 mail a@b.com "
				+ "Bearer abcdefghijklmnop",
			// 边界：数字串长度不足
			"123456789012345",
			// 边界：看起来像但不是
			"not-a-mobile 23812345678",
		};
		for (String sample : samples) {
			assertEquals(referenceMask(sample), optimizedMask(sample),
				"输入 [" + sample + "] 的脱敏结果与参考实现不一致 —— 优化改变了语义");
		}
	}

	@Test
	@DisplayName("预筛不命中时原样返回同一实例（不产生多余对象）")
	void returnsSameInstanceWhenNothingMatches() {
		RegexMaskGroup group = RegexMaskGroup.of(rules);
		String clean = "orderId=ORD-2024-000001&name=zhangsan";
		assertFalse(group.mayMatch(clean), "该文本不应命中任何规则");
		assertSame(clean, group.mask(clean, this::mask),
			"未命中时应返回原实例，避免无谓的字符串分配");
	}

	@Test
	@DisplayName("预筛命中时才逐条处理")
	void prefilterGatesExpensivePath() {
		RegexMaskGroup group = RegexMaskGroup.of(rules);
		assertTrue(group.mayMatch("contact 13812345678"));
		assertTrue(group.mask("contact 13812345678", this::mask).contains("*"));
	}

	@Test
	@DisplayName("空规则集：不报错、不改动文本")
	void handlesEmptyRuleSet() {
		RegexMaskGroup empty = RegexMaskGroup.of(List.of());
		assertTrue(empty.isEmpty());
		assertEquals("anything", empty.mask("anything", this::mask));
		assertFalse(empty.mayMatch("anything"));

		RegexMaskGroup nullRules = RegexMaskGroup.of(null);
		assertTrue(nullRules.isEmpty());
		assertEquals("x", nullRules.mask("x", this::mask));
	}

	@Test
	@DisplayName("null 与空文本安全处理")
	void handlesNullAndEmpty() {
		RegexMaskGroup group = RegexMaskGroup.of(rules);
		assertEquals(null, group.mask(null, this::mask));
		assertEquals("", group.mask("", this::mask));
		assertFalse(group.mayMatch(null));
	}
}
