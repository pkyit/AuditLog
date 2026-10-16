package io.github.pkyit.auditlog.core.mask;

import java.util.regex.Pattern;

/**
 * 基于正则的内容脱敏规则。
 *
 * <p>用于识别没有固定字段名的敏感内容：手机号、身份证号、银行卡号、邮箱、令牌串等。
 * 字段名规则只能覆盖「字段名已知」的场景，正则规则覆盖「字段名不可控」的场景，两者互补。
 *
 * <p>正则按原样编译执行，不做包装也不额外加锚：命中范围完全由 {@code pattern} 决定，
 * 需要整词匹配时必须在正则内部自行加边界（如 {@code \b}），否则会命中更长文本中的子串。
 *
 * @param name        规则名，用于指标与审计追溯
 * @param pattern     匹配模式，不可为 {@code null}
 * @param maskType    脱敏策略，不可为 {@code null}
 * @param keepPrefix  {@link MaskType#PARTIAL} 保留前缀，负数按 {@code 0} 处理
 * @param keepSuffix  {@link MaskType#PARTIAL} 保留后缀，负数按 {@code 0} 处理
 */
public record RegexMaskRule(String name, Pattern pattern, MaskType maskType, int keepPrefix, int keepSuffix) {

	/**
	 * 按正则字符串构建规则。
	 *
	 * @param name       规则名，用于指标与审计追溯
	 * @param regex      正则表达式，按原样编译
	 * @param maskType   脱敏策略，不可为 {@code null}
	 * @param keepPrefix {@link MaskType#PARTIAL} 保留前缀，负数按 {@code 0} 处理
	 * @param keepSuffix {@link MaskType#PARTIAL} 保留后缀，负数按 {@code 0} 处理
	 * @return 编译后的规则
	 * @throws NullPointerException {@code regex} 为 {@code null}
	 * @throws java.util.regex.PatternSyntaxException {@code regex} 不是合法正则
	 */
	public static RegexMaskRule of(String name, String regex, MaskType maskType, int keepPrefix, int keepSuffix) {
		return new RegexMaskRule(name, Pattern.compile(regex), maskType, keepPrefix, keepSuffix);
	}

	/** 中国大陆手机号，保留前 3 位与后 4 位。 */
	public static RegexMaskRule mobile() {
		return of("mobile", "1[3-9]\\d{9}", MaskType.PARTIAL, 3, 4);
	}

	/** 18 位身份证号，保留前 4 位与后 2 位。 */
	public static RegexMaskRule idCard() {
		return of("idCard", "[1-9]\\d{5}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]", MaskType.PARTIAL, 4, 2);
	}

	/** 银行卡号（16~19 位），保留前 4 位与后 4 位。 */
	public static RegexMaskRule bankCard() {
		return of("bankCard", "\\b\\d{16,19}\\b", MaskType.PARTIAL, 4, 4);
	}

	/** 邮箱，保留前 3 位、不保留后缀。 */
	public static RegexMaskRule email() {
		return of("email", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", MaskType.PARTIAL, 3, 0);
	}

	/** 承载令牌（Bearer / JWT），整段替换为掩码。 */
	public static RegexMaskRule bearerToken() {
		return of("bearerToken", "(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}", MaskType.FULL, 0, 0);
	}
}
