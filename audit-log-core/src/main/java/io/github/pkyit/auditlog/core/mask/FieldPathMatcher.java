package io.github.pkyit.auditlog.core.mask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 字段路径匹配器（glob 语义，按路径段逐一比较）。
 *
 * <p>匹配不能靠把 glob 翻译成锚定正则：配置 {@code $.token} 的意图是「任意层级上出现该名字」，
 * 而 {@code (?i)^\$\.[^.\[\]]+token$} 这类锚定正则只能匹配根下一层的 {@code token}。
 * 语义偏差的后果是「配置了敏感字段却依然原文落库」的静默泄漏，
 * 属于审计框架里最不可接受的一类错误，因此按路径段显式匹配。
 *
 * <p>匹配语义：
 * <ul>
 *   <li>{@code $} 根；{@code $.a.b} 表示「a 段之后紧跟 b 段」，其前可有任意前缀层级
 *       （即等价于 {@code **.a.b}）；</li>
 *   <li>以 {@code $} 开头且只写一段（{@code $.token}）→ 命中任意层级的 {@code token} 字段；</li>
 *   <li>{@code *} 匹配单个路径段（字段名或数组下标）；</li>
 *   <li>{@code **} 匹配零到多个路径段；</li>
 *   <li>{@code [n]} 匹配具体数组下标，{@code [*]} 匹配任意下标；</li>
 *   <li>字段名比较大小写不敏感（HTTP 头与 JSON 字段在实践中大小写混用）。</li>
 * </ul>
 *
 * <p>例：{@code $.data[*].idCard} 命中 {@code $.data[0].idCard}、{@code $.x.data[3].idCard}；
 * {@code **.password} 命中任意深度的 {@code password}。
 *
 * <p>实例不可变，编译结果可被多线程并发复用。
 */
public final class FieldPathMatcher {

	/** 段类型。 */
	private enum Kind {
		/** 具体字段名。 */
		NAME,
		/** 数组下标或字段名的通配（单段）。 */
		ANY_SEGMENT,
		/** 多段通配（**）。 */
		ANY_DEPTH
	}

	/** 单个匹配段。 */
	private record Segment(Kind kind, String name, int index, boolean anyIndex) {
	}

	private final List<Segment> segments;
	/**
	 * 是否为「非锚定」模式：为 {@code true} 时匹配允许任意前缀层级。
	 *
	 * <p>规则中只要未出现 {@code **} 即为 {@code true}。配置 {@code $.token} 的意图是
	 * 「任何层级的 token 字段都要脱敏」，按根下精确路径匹配会漏掉 {@code $.nested.token}，
	 * 而漏匹配在审计场景里等价于原文落库。
	 *
	 * <p>非锚定只放宽起始位置：匹配仍须覆盖到实际路径的末尾，
	 * 因此 {@code $.a.b} 命中 {@code $.x.a.b}，但不命中 {@code $.a.b.c}。
	 */
	private final boolean nonAnchored;

	private FieldPathMatcher(List<Segment> segments, boolean nonAnchored) {
		this.segments = List.copyOf(segments);
		this.nonAnchored = nonAnchored;
	}

	/**
	 * 编译一条 glob 表达式。
	 *
	 * <p>{@code glob} 为 {@code null}、空白或仅含 {@code $} 时得到空规则，
	 * 其 {@link #matches(String)} 恒返回 {@code false}。表达式中的多余空白与 {@code .} 被忽略。
	 *
	 * @param glob 形如 {@code $.data[*].idCard}、{@code **.password} 的路径表达式
	 * @return 不可变匹配器，可缓存复用
	 */
	public static FieldPathMatcher compile(String glob) {
		String path = glob == null ? "" : glob.trim();
		if (path.startsWith("$")) {
			path = path.substring(1);
		}
		List<Segment> segments = new ArrayList<>();
		int index = 0;
		while (index < path.length()) {
			char current = path.charAt(index);
			if (current == '.' || Character.isWhitespace(current)) {
				index++;
				continue;
			}
			if (current == '[') {
				int close = path.indexOf(']', index);
				if (close < 0) {
					// 缺少 ']' 时把剩余内容整体当作字段名：语法不完整不应导致整条规则被静默丢弃
					segments.add(new Segment(Kind.NAME, path.substring(index + 1), -1, false));
					break;
				}
				String inner = path.substring(index + 1, close).trim();
				if ("*".equals(inner)) {
					segments.add(new Segment(Kind.ANY_SEGMENT, null, -1, true));
				}
				else {
					try {
						segments.add(new Segment(Kind.NAME, null, Integer.parseInt(inner), false));
					}
					catch (NumberFormatException ignored) {
						segments.add(new Segment(Kind.NAME, inner, -1, false));
					}
				}
				index = close + 1;
				continue;
			}
			if (current == '*') {
				if (index + 1 < path.length() && path.charAt(index + 1) == '*') {
					segments.add(new Segment(Kind.ANY_DEPTH, null, -1, false));
					index += 2;
				}
				else {
					segments.add(new Segment(Kind.ANY_SEGMENT, null, -1, false));
					index++;
				}
				continue;
			}
			int next = index;
			while (next < path.length() && path.charAt(next) != '.' && path.charAt(next) != '['
					&& path.charAt(next) != '*') {
				next++;
			}
			segments.add(new Segment(Kind.NAME, path.substring(index, next), -1, false));
			index = next;
		}
		// 已显式包含 ** 的规则本身即「任意层级」语义，无需再放宽起始位置
		boolean hasAnyDepth = segments.stream().anyMatch(segment -> segment.kind() == Kind.ANY_DEPTH);
		return new FieldPathMatcher(segments, !hasAnyDepth);
	}

	/**
	 * 判断实际路径是否命中。
	 *
	 * <p>规则为空或 {@code actualPath} 为 {@code null}/空串时恒返回 {@code false}，不抛异常。
	 *
	 * @param actualPath 形如 {@code $.data[0].idCard} 的实际路径
	 * @return 命中返回 {@code true}
	 */
	public boolean matches(String actualPath) {
		if (actualPath == null || actualPath.isEmpty() || segments.isEmpty()) {
			return false;
		}
		// 快速拒绝：完整匹配需要把 actualPath 解析成段列表（分配 ArrayList），
		// 代价远高于一次字符串扫描。规则末段为具体字段名时，实际路径的末段必须同名
		// （忽略大小写），否则不可能命中；先做这一步必要条件检查可省掉整个解析与分配。
		Segment leaf = segments.get(segments.size() - 1);
		if (leaf.kind() == Kind.NAME && leaf.name() != null) {
			int lastDot = actualPath.lastIndexOf('.');
			int lastBracket = actualPath.lastIndexOf('[');
			int cut = Math.max(lastDot, lastBracket);
			if (cut >= 0 && cut + 1 < actualPath.length()) {
				String tail = actualPath.substring(cut + 1).trim();
				if (!leaf.name().equalsIgnoreCase(tail)) {
					return false;
				}
			}
			else {
				String tail = actualPath.startsWith("$") ? actualPath.substring(1) : actualPath;
				if (!leaf.name().equalsIgnoreCase(tail.trim())) {
					return false;
				}
			}
		}

		List<Segment> actual = parseActual(actualPath);
		if (nonAnchored) {
			// 允许任意前缀层级：从每个可能的起点尝试匹配
			for (int start = 0; start <= actual.size(); start++) {
				if (matchFrom(segments, 0, actual, start)) {
					return true;
				}
			}
			return false;
		}
		return matchFrom(segments, 0, actual, 0);
	}

	/**
	 * 从指定位置起递归匹配，要求规则段与实际段都被完整消费。
	 *
	 * <p>逐段比较；{@code **} 可消费 0..n 个实际路径段。实现为直接回溯且未做记忆化，
	 * 因此规则中 {@code **} 的数量越多，最坏情况下的回溯规模越大。
	 */
	private boolean matchFrom(List<Segment> pattern, int patternIndex, List<Segment> actual, int actualIndex) {
		if (patternIndex == pattern.size()) {
			return actualIndex == actual.size();
		}
		Segment current = pattern.get(patternIndex);
		if (current.kind() == Kind.ANY_DEPTH) {
			for (int skip = actualIndex; skip <= actual.size(); skip++) {
				if (matchFrom(pattern, patternIndex + 1, actual, skip)) {
					return true;
				}
			}
			return false;
		}
		if (actualIndex >= actual.size()) {
			return false;
		}
		if (!segmentMatches(current, actual.get(actualIndex))) {
			return false;
		}
		return matchFrom(pattern, patternIndex + 1, actual, actualIndex + 1);
	}

	private boolean segmentMatches(Segment pattern, Segment actual) {
		return switch (pattern.kind()) {
			case ANY_SEGMENT -> true;
			case ANY_DEPTH -> true;
			case NAME -> {
				if (pattern.anyIndex()) {
					yield actual.index() >= 0;
				}
				if (pattern.index() >= 0) {
					yield pattern.index() == actual.index();
				}
				yield pattern.name() != null && actual.name() != null
					&& pattern.name().equalsIgnoreCase(actual.name());
			}
		};
	}

	/**
	 * 把 {@code $.a[0].b} 解析为段序列。
	 *
	 * <p>未闭合的 {@code [} 被丢弃，其后内容按字段名解析；{@code [x]} 中 x 非数字时同样按字段名处理。
	 */
	private static List<Segment> parseActual(String path) {
		String value = path.startsWith("$") ? path.substring(1) : path;
		List<Segment> result = new ArrayList<>();
		StringBuilder name = new StringBuilder();
		int index = 0;
		while (index < value.length()) {
			char current = value.charAt(index);
			if (current == '.') {
				flushName(result, name);
				index++;
				continue;
			}
			if (current == '[') {
				flushName(result, name);
				int close = value.indexOf(']', index);
				if (close < 0) {
					index++;
					continue;
				}
				try {
					result.add(new Segment(Kind.NAME, null, Integer.parseInt(value.substring(index + 1, close).trim()),
						false));
				}
				catch (NumberFormatException ignored) {
					result.add(new Segment(Kind.NAME, value.substring(index + 1, close), -1, false));
				}
				index = close + 1;
				continue;
			}
			name.append(current);
			index++;
		}
		flushName(result, name);
		return result;
	}

	private static void flushName(List<Segment> target, StringBuilder name) {
		if (name.length() > 0) {
			target.add(new Segment(Kind.NAME, name.toString(), -1, false));
			name.setLength(0);
		}
	}

	/**
	 * 返回规范化后的段描述，用于诊断。
	 *
	 * @return 形如 {@code /data/[*]/idCard} 的字符串；空规则返回 {@code /}
	 */
	public String describe() {
		StringBuilder builder = new StringBuilder();
		for (Segment segment : segments) {
			builder.append('/');
			switch (segment.kind()) {
				case ANY_DEPTH -> builder.append("**");
				case ANY_SEGMENT -> builder.append(segment.anyIndex() ? "[*]" : "*");
				case NAME -> builder.append(segment.index() >= 0 ? "[" + segment.index() + "]"
					: segment.name());
			}
		}
		return builder.length() == 0 ? "/" : builder.toString();
	}

	@Override
	public String toString() {
		return "FieldPathMatcher[" + describe() + "]";
	}

	/**
	 * 便捷入口：编译并匹配，等价于 {@code compile(glob).matches(actualPath)}。
	 *
	 * <p>{@code glob} 为 {@code null} 时按空表达式处理，恒返回 {@code false}。
	 */
	public static boolean matches(String glob, String actualPath) {
		return compile(Objects.requireNonNullElse(glob, "")).matches(actualPath);
	}

	static String lower(String value) {
		return value == null ? null : value.toLowerCase(Locale.ROOT);
	}
}
