package io.github.pkyit.auditlog.core.mask;

import io.github.pkyit.auditlog.core.api.AuditMetrics;
import io.github.pkyit.auditlog.core.api.CaptureOptions;
import io.github.pkyit.auditlog.core.model.Payload;
import io.github.pkyit.auditlog.core.serialize.JacksonAuditSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuleBasedAuditMasker} 的行为测试。
 *
 * <p>固定以下契约边界：
 * <ol>
 *   <li>请求头匹配大小写不敏感，且核心黑名单不受配置影响 —— 大小写不一致的黑名单
 *       条目（如用小写 {@code authorization} 匹配 {@code Authorization}）会造成令牌泄漏；</li>
 *   <li>报文体同样属于脱敏范围 —— 仅处理请求头与查询串会让 body 中的
 *       password/idCard 原样入库。</li>
 * </ol>
 */
class RuleBasedAuditMaskerTest {

	private final RuleBasedAuditMasker masker = new RuleBasedAuditMasker(
		JacksonAuditSerializer.defaultMapper(), AuditMetrics.noop(),
		List.of(FieldMaskRule.full("$.password"), FieldMaskRule.full("$.token"),
			FieldMaskRule.full("$.idCard"), FieldMaskRule.full("$.mobile")));

	private CaptureOptions options() {
		return new CaptureOptions(true, false, true, 4096, 8, false, 1024,
			List.of(FieldMaskRule.full("$.password"), FieldMaskRule.full("$.token"),
				FieldMaskRule.full("$.idCard"), FieldMaskRule.full("$.mobile")),
			List.of(), Set.of("x-custom-secret"), Set.of());
	}

	private Payload json(String body) {
		return new Payload("json", body, body.length(), false);
	}

	@Test
	@DisplayName("报文体中的敏感字段被掩码，非敏感字段保留")
	void masksSensitiveBodyFields() {
		String masked = masker.maskPayload(json("{\"username\":\"zhangsan\",\"password\":\"P@ssw0rd\","
			+ "\"idCard\":\"110101199003071234\",\"mobile\":\"13812345678\"}"), options()).body();
		assertFalse(masked.contains("P@ssw0rd"), "password 不得出现在审计载荷中");
		assertFalse(masked.contains("110101199003071234"), "idCard 不得出现");
		assertFalse(masked.contains("13812345678"), "mobile 不得出现");
		assertTrue(masked.contains("zhangsan"), "非敏感字段应保留（否则审计失去价值）");
	}

	@Test
	@DisplayName("嵌套对象中的敏感字段也被掩码（回归：曾经只匹配单层路径）")
	void masksNestedSensitiveFields() {
		String masked = masker.maskPayload(json("{\"data\":{\"profile\":{\"token\":\"secret-token-value\"}}}"),
			options()).body();
		assertFalse(masked.contains("secret-token-value"), "嵌套 token 必须被掩码");
	}

	@Test
	@DisplayName("数组元素中的敏感字段也被掩码")
	void masksSensitiveFieldsInArrays() {
		String masked = masker.maskPayload(
			json("{\"list\":[{\"mobile\":\"13900000001\"},{\"mobile\":\"13900000002\"}]}"), options()).body();
		assertFalse(masked.contains("13900000001"));
		assertFalse(masked.contains("13900000002"));
	}

	@Test
	@DisplayName("请求头匹配大小写不敏感，且核心黑名单不可被配置绕过")
	void masksHeadersCaseInsensitively() {
		Map<String, String> masked = masker.maskHeaders(Map.of(
			"Authorization", "Bearer leaked-token",
			"Cookie", "JSESSIONID=leaked-session",
			"X-API-Key", "leaked-api-key",
			"X-Custom-Secret", "leaked-custom",
			"Content-Type", "application/json"), options());
		assertFalse(masked.get("Authorization").contains("leaked-token"),
			"Authorization 必须被掩码（回归：曾经因大小写敏感而泄漏）");
		assertFalse(masked.get("Cookie").contains("leaked-session"), "Cookie 必须被掩码");
		assertFalse(masked.get("X-API-Key").contains("leaked-api-key"), "X-API-Key 必须被掩码");
		assertFalse(masked.get("X-Custom-Secret").contains("leaked-custom"), "自定义敏感头必须被掩码");
		assertEquals("application/json", masked.get("Content-Type"), "非敏感头应保留原值");
	}

	@Test
	@DisplayName("查询串中的敏感参数被掩码")
	void masksSensitiveQueryParameters() {
		String masked = masker.maskQueryString("userName=zhangsan&password=P%40ssw0rd&idCard=110101199003071234",
			options());
		assertFalse(masked.contains("P%40ssw0rd"), "查询串中的 password 必须被掩码");
		assertFalse(masked.contains("110101199003071234"), "查询串中的 idCard 必须被掩码");
		assertTrue(masked.contains("userName=zhangsan"), "非敏感查询参数应保留");
	}

	@Test
	@DisplayName("内容级正则识别没有固定字段名的敏感内容")
	void masksSensitiveContentByRegex() {
		CaptureOptions withRegex = new CaptureOptions(true, false, true, 4096, 8, false, 1024,
			List.of(), List.of(RegexMaskRule.mobile(), RegexMaskRule.bearerToken()), Set.of(), Set.of());
		String masked = masker.maskPayload(json("{\"note\":\"contact 13812345678 with Bearer abcdefghijklmnop\"}"),
			withRegex).body();
		assertFalse(masked.contains("13812345678"), "手机号应被内容正则掩码");
		assertFalse(masked.contains("abcdefghijklmnop"), "Bearer 令牌应被内容正则掩码");
	}

	@Test
	@DisplayName("非法 JSON 载荷降级为内容级脱敏，而不是抛异常或落原文")
	void handlesInvalidJson() {
		CaptureOptions withRegex = new CaptureOptions(true, false, true, 4096, 8, false, 1024,
			List.of(), List.of(RegexMaskRule.mobile()), Set.of(), Set.of());
		Payload masked = masker.maskPayload(new Payload("json", "not-json-but-has-13812345678", 26, false),
			withRegex);
		assertFalse(masked.body().contains("13812345678"), "非法 JSON 也应完成内容级脱敏");
	}

	@Test
	@DisplayName("超长载荷被截断并标记 truncated")
	void truncatesOversizedPayload() {
		CaptureOptions small = new CaptureOptions(true, false, true, 32, 8, false, 1024,
			List.of(), List.of(), Set.of(), Set.of());
		Payload masked = masker.maskPayload(json("{\"data\":\"" + "x".repeat(500) + "\"}"), small);
		assertTrue(masked.truncated(), "超出上限应标记为截断，而不是让消费方误以为完整");
		assertTrue(masked.body().length() <= 32 + "...(truncated)".length());
	}
}
