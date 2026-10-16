package io.github.pkyit.auditlog.core.mask;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 内容级脱敏规则组：以「合并预筛 + 按需逐条替换」替代「逐条全量扫描」。
 *
 * <p>内容级脱敏需要在每个字符串上跑多条正则（Bearer 令牌、身份证、银行卡、手机号、邮箱）。
 * 逐条各自完整扫描会把同一段文本重复扫描规则数次，而绝大多数值并不命中任何规则，
 * 这部分开销纯属浪费。因此把全部规则合并为一个「非捕获组择一」的预筛正则先扫一遍，
 * 成本从 O(规则数 × 长度) 降到 O(长度)；只有预筛命中时才逐条执行替换，
 * 额外开销只落在少数值上。
 *
 * <p>预筛是各规则式的并集，因此「预筛不命中 ⇒ 任何规则都不命中」成立，
 * 跳过逐条替换不会漏掉命中。逐条替换按规则传入顺序执行，后一条在前一条的结果上继续处理。
 *
 * <p>实例不可变，{@link #mask(String, Replacer)} 可被多线程并发调用。
 */
public final class RegexMaskGroup {

	private final List<RegexMaskRule> rules;
	private final Pattern prefilter;

	private RegexMaskGroup(List<RegexMaskRule> rules, Pattern prefilter) {
		this.rules = rules;
		this.prefilter = prefilter;
	}

	/**
	 * 用一组规则构建，构造时一次性完成合并预筛。
	 *
	 * @param rules 内容级规则；为 {@code null} 或空集合时返回恒不命中的实例
	 * @return 不可变实例，可作为单例长期复用
	 */
	public static RegexMaskGroup of(List<RegexMaskRule> rules) {
		if (rules == null || rules.isEmpty()) {
			return new RegexMaskGroup(List.of(), null);
		}
		StringBuilder combined = new StringBuilder();
		for (RegexMaskRule rule : rules) {
			if (rule == null || rule.pattern() == null) {
				continue;
			}
			if (combined.length() > 0) {
				combined.append('|');
			}
			combined.append("(?:").append(rule.pattern().pattern()).append(')');
		}
		Pattern prefilter = combined.length() == 0 ? null : Pattern.compile(combined.toString());
		return new RegexMaskGroup(List.copyOf(rules), prefilter);
	}

	/**
	 * 是否没有可执行的内容规则。
	 *
	 * @return 为 {@code true} 时 {@link #mayMatch(CharSequence)} 恒返回 {@code false}，
	 *         {@link #mask(String, Replacer)} 原样返回输入
	 */
	public boolean isEmpty() {
		return rules.isEmpty() || prefilter == null;
	}

	/**
	 * 预筛是否命中，可用于诊断或快速判断。
	 *
	 * <p>命中只表示可能存在内容级命中，具体命中哪条规则仍需逐条匹配。
	 *
	 * @param text 待检查文本；为 {@code null} 或规则组为空时返回 {@code false}
	 */
	public boolean mayMatch(CharSequence text) {
		return !isEmpty() && text != null && prefilter.matcher(text).find();
	}

	/**
	 * 对文本执行内容级脱敏。
	 *
	 * @param text    待处理文本，可为 {@code null}
	 * @param applier 命中片段 → 替换文本的函数，对每个命中片段调用一次；
	 *                返回值按字面量插入，其中的 {@code $} 与 {@code \} 不按正则替换语法解释
	 * @return 脱敏后的文本；未命中任何规则时原样返回入参（同一实例，不产生新对象）
	 */
	public String mask(String text, Replacer applier) {
		if (isEmpty() || text == null || text.isEmpty()) {
			return text;
		}
		// 预筛未命中即无任何规则命中，直接返回，无需构造逐条替换的开销
		if (!prefilter.matcher(text).find()) {
			return text;
		}
		String result = text;
		for (RegexMaskRule rule : rules) {
			if (rule == null || rule.pattern() == null) {
				continue;
			}
			java.util.regex.Matcher matcher = rule.pattern().matcher(result);
			StringBuilder builder = null;   // 仅在确有命中时才分配
			while (matcher.find()) {
				if (builder == null) {
					builder = new StringBuilder(result.length() + 16);
				}
				String replacement = applier.apply(matcher.group(), rule);
				matcher.appendReplacement(builder, java.util.regex.Matcher.quoteReplacement(replacement));
			}
			if (builder != null) {
				matcher.appendTail(builder);
				result = builder.toString();
			}
		}
		return result;
	}

	/** 规则数量（用于诊断）。 */
	public int ruleCount() {
		return rules.size();
	}

	/**
	 * 命中片段 → 替换文本。
	 *
	 * <p>实现不得返回 {@code null}：返回值由
	 * {@link java.util.regex.Matcher#quoteReplacement(String)} 转义后写入结果。
	 */
	@FunctionalInterface
	public interface Replacer {
		String apply(String matched, RegexMaskRule rule);
	}
}
