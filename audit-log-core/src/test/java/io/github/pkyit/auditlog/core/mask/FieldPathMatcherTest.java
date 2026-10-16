package io.github.pkyit.auditlog.core.mask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FieldPathMatcher} 的语义测试。
 *
 * <p>路径匹配采用非锚定语义：规则中未显式书写 {@code **} 时按字段名匹配任意深度，
 * 因此 {@code $.token} 必须能命中 {@code $.nested.token}。若匹配被锚定在根层级，
 * 嵌套结构中的敏感字段将不再被掩码，并因原文落库而表现为静默数据泄漏，
 * 且仅覆盖单层路径的用例无法发现该退化。
 *
 * <p>用例逐条固定嵌套、数组、大小写与通配四种路径形态的匹配边界。
 */
class FieldPathMatcherTest {

	@Nested
	@DisplayName("非锚定语义：未写 ** 的规则匹配任意层级")
	class NonAnchoredSemantics {

		@Test
		@DisplayName("$.token 应命中根下的 token（回归：曾经的单层匹配）")
		void matchesRootLevelField() {
			assertTrue(FieldPathMatcher.matches("$.token", "$.token"));
		}

		@Test
		@DisplayName("$.token 应命中 $.nested.token（回归：曾经的正则锚定缺陷）")
		void matchesNestedField() {
			assertTrue(FieldPathMatcher.matches("$.token", "$.nested.token"));
		}

		@Test
		@DisplayName("$.token 应命中深层嵌套 $.a.b.c.token")
		void matchesDeeplyNestedField() {
			assertTrue(FieldPathMatcher.matches("$.token", "$.a.b.c.token"));
		}

		@Test
		@DisplayName("$.token 应命中数组元素内的 token")
		void matchesFieldInsideArray() {
			assertTrue(FieldPathMatcher.matches("$.token", "$.list[0].token"));
		}

		@Test
		@DisplayName("不应命中名字不同的字段")
		void doesNotMatchDifferentName() {
			assertFalse(FieldPathMatcher.matches("$.token", "$.tokens"));
			assertFalse(FieldPathMatcher.matches("$.token", "$.accessToken"));
			assertFalse(FieldPathMatcher.matches("$.password", "$.userName"));
		}

		@Test
		@DisplayName("不应命中把字段名当作值出现的情况（路径是按段比较的）")
		void doesNotMatchWhenNameAppearsAsValue() {
			// 路径按段比较：字段值中出现 token 不构成命中
			assertFalse(FieldPathMatcher.matches("$.token", "$.note"));
		}
	}

	@Nested
	@DisplayName("大小写不敏感")
	class CaseInsensitivity {

		@Test
		@DisplayName("规则与路径大小写不同也应命中")
		void matchesRegardlessOfCase() {
			assertTrue(FieldPathMatcher.matches("$.password", "$.Password"));
			assertTrue(FieldPathMatcher.matches("$.Password", "$.password"));
			assertTrue(FieldPathMatcher.matches("$.PASSWORD", "$.nested.pAssWord"));
		}
	}

	@Nested
	@DisplayName("锚定语义：显式书写多段与 ** 时按段匹配")
	class AnchoredSemantics {

		@Test
		@DisplayName("$.data[*].idCard 命中数组元素")
		void matchesArrayWildcard() {
			assertTrue(FieldPathMatcher.matches("$.data[*].idCard", "$.data[0].idCard"));
			assertTrue(FieldPathMatcher.matches("$.data[*].idCard", "$.data[42].idCard"));
		}

		@Test
		@DisplayName("$.data[*].idCard 命中带前缀层级的路径")
		void matchesArrayWildcardWithPrefix() {
			assertTrue(FieldPathMatcher.matches("$.data[*].idCard", "$.x.data[3].idCard"));
		}

		@Test
		@DisplayName("$.data[*].idCard 不应命中层级不足的路径")
		void doesNotMatchIncompletePath() {
			assertFalse(FieldPathMatcher.matches("$.data[*].idCard", "$.data.idCard"));
		}

		@Test
		@DisplayName("$.data[1].idCard 只命中指定下标")
		void matchesSpecificIndex() {
			assertTrue(FieldPathMatcher.matches("$.data[1].idCard", "$.data[1].idCard"));
			assertFalse(FieldPathMatcher.matches("$.data[1].idCard", "$.data[2].idCard"));
		}

		@Test
		@DisplayName("**.password 命中任意深度")
		void doubleStarMatchesAnyDepth() {
			assertTrue(FieldPathMatcher.matches("**.password", "$.password"));
			assertTrue(FieldPathMatcher.matches("**.password", "$.a.password"));
			assertTrue(FieldPathMatcher.matches("**.password", "$.a.b.c.password"));
		}

		@Test
		@DisplayName("$.user.password 限定在 user 之下")
		void scopedPath() {
			assertTrue(FieldPathMatcher.matches("$.user.password", "$.user.password"));
			// 规则匹配任意深度，故 user.password 允许携带任意前缀段
			assertTrue(FieldPathMatcher.matches("$.user.password", "$.tenant.user.password"));
			// 但 user 与 password 之间不得插入其它段
			assertFalse(FieldPathMatcher.matches("$.user.password", "$.user.profile.password"));
		}

		@Test
		@DisplayName("* 匹配单个任意字段名")
		void singleStarMatchesOneSegment() {
			assertTrue(FieldPathMatcher.matches("$.user.*", "$.user.idCard"));
			assertFalse(FieldPathMatcher.matches("$.user.*", "$.user.profile.idCard"));
		}
	}

	@Nested
	@DisplayName("边界与健壮性")
	class Robustness {

		@Test
		@DisplayName("空规则或空路径不应命中，也不应抛异常")
		void handlesEmptyInput() {
			assertFalse(FieldPathMatcher.matches("", "$.token"));
			assertFalse(FieldPathMatcher.matches("$.token", ""));
			assertFalse(FieldPathMatcher.matches(null, "$.token"));
			assertFalse(FieldPathMatcher.matches("$.token", null));
		}

		@Test
		@DisplayName("不完整的 [ 语法不应导致整条规则被静默忽略")
		void handlesMalformedBracket() {
			// `[` 不完整时按字段名处理，返回 false 而非抛异常
			FieldPathMatcher matcher = FieldPathMatcher.compile("$.data[0.idCard");
			assertFalse(matcher.matches("$.data[0].idCard"));
		}

		@Test
		@DisplayName("规则可被描述（便于诊断输出）")
		void describeIsReadable() {
			assertTrue(FieldPathMatcher.compile("$.data[*].idCard").describe().contains("idCard"));
			assertTrue(FieldPathMatcher.compile("**.password").describe().contains("**"));
		}
	}
}
