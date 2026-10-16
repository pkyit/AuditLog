package io.github.pkyit.auditlog.spring.properties;

import io.github.pkyit.auditlog.core.mask.FieldMaskRule;
import io.github.pkyit.auditlog.core.mask.MaskType;
import io.github.pkyit.auditlog.core.mask.RegexMaskRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 派生配置记忆化的测试。
 *
 * <p>这些方法在采集路径上被每个请求多次调用；未命中缓存时会重建规则列表并重新编译正则，
 * {@code FieldMaskRule}/{@code RegexMaskRule}/{@code Pattern} 的分配因此集中在热点路径上。
 * 记忆化是纯优化，必须保证：
 * <ol>
 *   <li>返回同一实例（真的缓存住了，否则优化无效）；</li>
 *   <li>内容与未缓存时一致（语义不变）；</li>
 *   <li>并发下只计算一次且安全发布（否则可能出现半构造对象）。</li>
 * </ol>
 */
class AuditLogPropertiesMaskingCacheTest {

	@Test
	@DisplayName("字段规则与内容规则被缓存：重复调用返回同一实例")
	void cachesDerivedRules() {
		AuditLogProperties properties = new AuditLogProperties();
		AuditLogProperties.Masking masking = properties.getMasking();

		List<FieldMaskRule> first = masking.toFieldRules();
		assertSame(first, masking.toFieldRules(), "字段规则应被记忆化（否则每请求都在重建列表）");

		List<RegexMaskRule> regexFirst = masking.toRegexRules();
		assertSame(regexFirst, masking.toRegexRules(), "内容规则应被记忆化（否则每请求都在重新编译正则）");

		Set<String> headersFirst = masking.sensitiveHeadersLowercased();
		assertSame(headersFirst, masking.sensitiveHeadersLowercased(), "敏感头集合应被记忆化");
	}

	@Test
	@DisplayName("缓存内容与配置一致：默认敏感字段与内置内容正则齐备")
	void cachedContentMatchesConfiguration() {
		AuditLogProperties properties = new AuditLogProperties();
		AuditLogProperties.Masking masking = properties.getMasking();

		List<FieldMaskRule> rules = masking.toFieldRules();
		assertTrue(rules.stream().anyMatch(r -> "$.password".equals(r.fieldPath())),
			"默认应包含 password 规则");
		assertTrue(rules.stream().anyMatch(r -> "$.idCard".equals(r.fieldPath())),
			"默认应包含 idCard 规则");
		assertTrue(rules.stream().allMatch(r -> r.maskType() == MaskType.FULL),
			"默认策略应为 FULL");

		List<RegexMaskRule> regexRules = masking.toRegexRules();
		assertEquals(5, regexRules.size(), "默认应启用 5 条内置内容正则（Bearer/身份证/银行卡/手机号/邮箱）");
	}

	@Test
	@DisplayName("fieldRuleOverrides 生效，且去重逻辑不产生重复规则")
	void appliesOverridesWithoutDuplicates() {
		AuditLogProperties properties = new AuditLogProperties();
		AuditLogProperties.Masking masking = properties.getMasking();
		masking.setSensitiveFields(List.of("$.password", "$.password", "$.customToken"));
		masking.setFieldRuleOverrides(List.of("$.customToken=PARTIAL", "$.onlyInOverrides=HASH"));

		List<FieldMaskRule> rules = masking.toFieldRules();

		assertEquals(3, rules.size(), "重复声明的字段应被去重；overrides 中独有的字段应被补入");
		assertEquals(1, rules.stream().filter(r -> "$.password".equals(r.fieldPath())).count());
		assertEquals(MaskType.PARTIAL,
			rules.stream().filter(r -> "$.customToken".equals(r.fieldPath())).findFirst().orElseThrow().maskType());
		assertEquals(MaskType.HASH,
			rules.stream().filter(r -> "$.onlyInOverrides".equals(r.fieldPath())).findFirst().orElseThrow()
				.maskType());
	}

	@Test
	@DisplayName("并发首次调用只产生一个实例（无竞态、无半构造对象）")
	void concurrentFirstCallPublishesSingleInstance() throws Exception {
		AuditLogProperties properties = new AuditLogProperties();
		AuditLogProperties.Masking masking = properties.getMasking();

		int threads = 16;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch startGate = new CountDownLatch(1);
		AtomicReference<List<RegexMaskRule>> reference = new AtomicReference<>();
		java.util.List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

		try {
			for (int i = 0; i < threads; i++) {
				pool.submit(() -> {
					try {
						startGate.await();
						List<RegexMaskRule> rules = masking.toRegexRules();
						// 校验拿到的是完整可用的对象，而不是半构造状态
						assertEquals(5, rules.size());
						reference.compareAndSet(null, rules);
					}
					catch (Throwable error) {
						errors.add(error);
					}
				});
			}
			startGate.countDown();
			pool.shutdown();
			assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "并发任务应在超时前完成");
		}
		finally {
			pool.shutdownNow();
		}

		assertTrue(errors.isEmpty(), "并发调用不得抛异常：" + errors);
		assertEquals(5, reference.get().size());
	}

	@Test
	@DisplayName("关闭正则规则时缓存为空，且不会误判为已启用")
	void cachesEmptyWhenRegexDisabled() {
		AuditLogProperties properties = new AuditLogProperties();
		AuditLogProperties.Masking masking = properties.getMasking();
		masking.setRegexRulesEnabled(false);

		assertTrue(masking.toRegexRules().isEmpty(), "关闭后应无内容正则规则");
		// 再确认重复调用仍为空（缓存生效且不会把空列表当成"未初始化"）
		assertTrue(masking.toRegexRules().isEmpty());
		assertSame(masking.toRegexRules(), masking.toRegexRules());
	}

	@Test
	@DisplayName("非法 override 被忽略但缓存仍可用（启动校验负责告警）")
	void toleratesInvalidOverrides() {
		AuditLogProperties properties = new AuditLogProperties();
		AuditLogProperties.Masking masking = properties.getMasking();
		masking.setFieldRuleOverrides(List.of("没有等号", "$.password=NOT_A_STRATEGY", "$.ok=PARTIAL"));

		List<FieldMaskRule> rules = masking.toFieldRules();
		assertNotSame(0, rules.size());
		assertEquals(MaskType.FULL,
			rules.stream().filter(r -> "$.password".equals(r.fieldPath())).findFirst().orElseThrow().maskType(),
			"非法策略应回退为 FULL，而不是让整条规则消失");
		assertEquals(MaskType.PARTIAL,
			rules.stream().filter(r -> "$.ok".equals(r.fieldPath())).findFirst().orElseThrow().maskType());
	}
}
