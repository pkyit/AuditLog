package io.github.pkyit.auditlog.core.mask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.pkyit.auditlog.core.api.AuditMasker;
import io.github.pkyit.auditlog.core.api.CaptureOptions;
import io.github.pkyit.auditlog.core.api.AuditMetricNames;
import io.github.pkyit.auditlog.core.api.AuditMetrics;
import io.github.pkyit.auditlog.core.model.Payload;
import io.github.pkyit.auditlog.core.serialize.JacksonAuditSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于规则的默认脱敏实现：字段路径规则 + 内容正则规则 + 请求头名单。
 *
 * <p>处理顺序固定为两步：先按 JSON 树做字段级脱敏，再对序列化后的文本做内容级正则脱敏。
 * 顺序不可颠倒：先做正则替换会破坏 JSON 结构，使后续的字段路径匹配失效；
 * 字段级脱敏只替换节点的值或删除节点，不触碰结构本身。
 *
 * <p>字段名与请求头名一律按大小写不敏感比较：HTTP 头与 JSON 字段在实践中大小写混用，
 * 精确匹配会漏掉 {@code Authorization} 这类真实凭据，造成原文落库。
 *
 * <p>降级策略：任一步失败都不回退为原文。JSON 树脱敏失败时先退到内容级正则，
 * 内容级也失败时整段替换为 {@code FULL_MASK}。
 *
 * <p>除匹配器缓存（{@code ConcurrentHashMap}）外无可变状态，可被多线程并发调用。
 */
public class RuleBasedAuditMasker implements AuditMasker {

	private static final Logger log = LoggerFactory.getLogger(RuleBasedAuditMasker.class);

	/**
	 * 强制脱敏的请求头，不受用户配置覆盖。
	 *
	 * <p>这是不可关闭的安全底线：用户在配置中写错大小写或漏配时，这些头仍必须被掩码，
	 * 否则一次配置失误就是一次凭据泄漏。集合元素均为小写名称，比较前需先归一化大小写。
	 */
	public static final Set<String> MANDATORY_SENSITIVE_HEADERS = Set.of(
		"authorization", "proxy-authorization", "cookie", "set-cookie",
		"x-auth-token", "x-access-token", "x-api-key", "api-key",
		"x-csrf-token", "x-xsrf-token", "x-session-id", "x-secret");

	private static final String FULL_MASK = "******";

	/**
	 * 是否启用自定义脱敏（字段规则 + 内容正则 + 用户追加的敏感头）。
	 *
	 * <p>本开关只控制用户可配置的部分，不影响 {@link #MANDATORY_SENSITIVE_HEADERS}
	 * （Authorization、Cookie、API-Key 等）：凭据类请求头无论本开关取何值都会被掩码。
	 *
	 * <p>为 {@code false} 时 {@link #maskPayload} 与 {@link #maskQueryString} 原样返回输入。
	 */
	private final boolean customMaskingEnabled;

	private final ObjectMapper objectMapper;
	private final AuditMetrics metrics;
	private final List<CompiledFieldRule> compiledRules;

	/**
	 * 字段路径 → 已编译匹配器的缓存。
	 *
	 * <p>{@code matchRule} 会被「每个字段 × 每条规则」调用，在采集热点上重复
	 * {@code FieldPathMatcher.compile} 的开销不可忽略，因此按字段路径缓存编译结果。
	 *
	 * <p>缓存无淘汰：键来自调用方传入的字段规则路径，路径集合稳定时条目数有界；
	 * 若每次调用都传入大量互不相同的路径，条目数会随之增长。
	 */
	private final java.util.concurrent.ConcurrentHashMap<String, FieldPathMatcher> matcherCache =
		new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * 内容级规则组（合并预筛）。为 {@code null} 时表示未由装配方固定规则，
	 * 此时退回按 {@link CaptureOptions#regexRules()} 逐次执行。
	 */
	private final RegexMaskGroup regexGroup;

	/** 使用默认 {@code ObjectMapper}、noop 指标与空规则构建。 */
	public RuleBasedAuditMasker() {
		this(JacksonAuditSerializer.defaultMapper(), AuditMetrics.noop(), List.of());
	}

	/**
	 * 创建不带实例级内容规则的脱敏器，内容规则逐次取自
	 * {@link CaptureOptions#regexRules()}。
	 *
	 * @param objectMapper 用于 JSON 树解析与序列化
	 * @param metrics      脱敏计数指标
	 * @param baseRules    实例级固定字段规则，可为 {@code null}
	 */
	public RuleBasedAuditMasker(ObjectMapper objectMapper, AuditMetrics metrics, List<FieldMaskRule> baseRules) {
		this(objectMapper, metrics, baseRules, null);
	}

	/**
	 * @param objectMapper 用于 JSON 树解析与序列化
	 * @param metrics      脱敏计数指标
	 * @param baseRules    实例级固定字段规则，可为 {@code null}
	 * @param contentRules 内容级脱敏规则；为 {@code null} 时退回「按 {@link CaptureOptions}
	 *                     逐次执行」的兼容路径。装配方传入后合并预筛只构建一次
	 */
	public RuleBasedAuditMasker(ObjectMapper objectMapper, AuditMetrics metrics, List<FieldMaskRule> baseRules,
			List<RegexMaskRule> contentRules) {
		this(objectMapper, metrics, baseRules, contentRules, true);
	}

	/**
	 * @param objectMapper         用于 JSON 树解析与序列化
	 * @param metrics              脱敏计数指标
	 * @param baseRules            实例级固定字段规则，可为 {@code null}
	 * @param contentRules         内容级脱敏规则，可为 {@code null}
	 * @param customMaskingEnabled {@code false} 时跳过字段规则与内容正则（用于
	 *                             {@code audit.log.masking.enabled=false}）；凭据类请求头仍会被强制掩码
	 */
	public RuleBasedAuditMasker(ObjectMapper objectMapper, AuditMetrics metrics, List<FieldMaskRule> baseRules,
			List<RegexMaskRule> contentRules, boolean customMaskingEnabled) {
		this.objectMapper = objectMapper;
		this.metrics = metrics;
		this.customMaskingEnabled = customMaskingEnabled;
		List<CompiledFieldRule> rules = new ArrayList<>();
		if (baseRules != null) {
			for (FieldMaskRule rule : baseRules) {
				rules.add(new CompiledFieldRule(rule, FieldPathMatcher.compile(rule.fieldPath())));
			}
		}
		this.compiledRules = List.copyOf(rules);
		this.regexGroup = contentRules == null || contentRules.isEmpty()
			? null
			: RegexMaskGroup.of(contentRules);
		log.info("[audit-log] 脱敏引擎已初始化：基础字段规则 {} 条，内容正则规则 {} 条{}",
			this.compiledRules.size(), this.regexGroup == null ? 0 : this.regexGroup.ruleCount(),
			this.compiledRules.isEmpty() ? "" : "，" + this.compiledRules.stream()
				.map(compiled -> compiled.rule().fieldPath()).toList());
	}

	/**
	 * 列出已装载的字段规则，用于诊断配置是否生效。
	 *
	 * @return 每次调用新建的列表，元素形如 {@code $.token=>FULL}，调用方可自由修改
	 */
	public List<String> ruleSummary() {
		List<String> summary = new ArrayList<>(compiledRules.size());
		for (CompiledFieldRule rule : compiledRules) {
			summary.add(rule.rule().fieldPath() + "=>" + rule.rule().maskType());
		}
		return summary;
	}

	/**
	 * 对载荷执行脱敏。
	 *
	 * <p>{@code payload} 为 {@code null} 时返回 {@link Payload#EMPTY}，为空载荷时原样返回；
	 * 自定义脱敏关闭时（见 {@link #customMaskingEnabled}）同样原样返回。
	 *
	 * <p>返回的 {@link Payload} 是新实例（空载荷与关闭开关的情形原样返回入参）；
	 * 正文超过 {@link CaptureOptions#maxBodyLength()} 时截断并置位 {@code truncated}。
	 * 脱敏失败时不返回原文，最差情况是整段掩码。
	 *
	 * @param payload 待脱敏载荷
	 * @param options 本次采集的选项，提供调用方级字段规则与内容正则规则
	 * @return 脱敏后的载荷
	 */
	@Override
	public Payload maskPayload(Payload payload, CaptureOptions options) {
		if (payload == null || payload.isEmpty()) {
			return payload == null ? Payload.EMPTY : payload;
		}
		// 自定义脱敏关闭时原样返回：该开关的语义就是保留原文，配置必须真实生效
		if (!customMaskingEnabled) {
			return payload;
		}
		String original = payload.body();
		if (!"json".equals(payload.contentType())) {
			return rebuild(payload, applyRegexRules(original, options), options);
		}
		try {
			return rebuild(payload, maskJsonText(original, options), options);
		}
		catch (Throwable treeError) {
			// JSON 树脱敏失败（非法 JSON、超深或被截断）时先退到内容级正则脱敏，
			// 保留可用信息；只有正则脱敏本身也失败时才整段掩码：
			// 内容可以丢，原文不能落库。
			try {
				log.debug("[audit-log] 载荷非合法 JSON，降级为内容级脱敏：{}", treeError.toString());
				return rebuild(payload, applyRegexRules(original, options), options);
			}
			catch (Throwable regexError) {
				log.warn("[audit-log] 载荷脱敏失败，降级为全文掩码：{}", regexError.toString());
				metrics.increment(AuditMetricNames.MASKED);
				return rebuild(payload, FULL_MASK, options);
			}
		}
	}

	private String maskJsonText(String json, CaptureOptions options) throws Exception {
		JsonNode root = objectMapper.readTree(json);
		if (root == null) {
			return applyRegexRules(json, options);
		}
		maskNode(root, "$", options);
		return applyRegexRules(objectMapper.writeValueAsString(root), options);
	}

	private void maskNode(JsonNode node, String path, CaptureOptions options) {
		if (node instanceof ObjectNode objectNode) {
			List<String> fields = new ArrayList<>();
			Iterator<String> names = objectNode.fieldNames();
			while (names.hasNext()) {
				fields.add(names.next());
			}
			for (String field : fields) {
				String childPath = "$".equals(path) ? "$." + field : path + "." + field;
				MaskType rule = matchRule(childPath, options);
				if (rule == MaskType.REMOVE) {
					objectNode.remove(field);
					metrics.increment(AuditMetricNames.MASKED);
					continue;
				}
				if (rule != null) {
					JsonNode child = objectNode.get(field);
					if (child != null && child.isValueNode()) {
						objectNode.set(field, TextNode.valueOf(applyMask(child.asText(), rule, options)));
						metrics.increment(AuditMetricNames.MASKED);
						continue;
					}
					if (child != null && !child.isValueNode()) {
						// 非叶子节点被整体命中时替换为掩码，不下钻其子结构，避免结构泄漏
						objectNode.set(field, TextNode.valueOf(FULL_MASK));
						metrics.increment(AuditMetricNames.MASKED);
						continue;
					}
				}
				// 路径长度达到 2048 后停止下钻：更深层级的字段不再参与字段级脱敏，
				// 但整个文本仍会被后续的内容级正则处理
				if (childPath.length() < 2048) {
					maskNode(objectNode.get(field), childPath, options);
				}
			}
			return;
		}
		if (node instanceof ArrayNode arrayNode) {
			for (int index = 0; index < arrayNode.size(); index++) {
				JsonNode child = arrayNode.get(index);
				String childPath = path + "[" + index + "]";
				MaskType rule = matchRule(childPath, options);
				if (rule != null && child != null && child.isValueNode()) {
					arrayNode.set(index, TextNode.valueOf(applyMask(child.asText(), rule, options)));
					metrics.increment(AuditMetricNames.MASKED);
					continue;
				}
				if (child != null) {
					maskNode(child, childPath, options);
				}
			}
		}
	}

	/**
	 * 判断给定字段路径是否命中规则。
	 *
	 * <p>顺序固定：先查实例级固定规则，再查本次调用携带的规则，返回首个命中者的策略。
	 *
	 * @return 命中的脱敏策略；未命中返回 {@code null}
	 */
	private MaskType matchRule(String path, CaptureOptions options) {
		// 1) 实例级固定规则优先，确保全局配置不被调用方规则覆盖
		for (CompiledFieldRule rule : compiledRules) {
			if (rule.matcher().matches(path)) {
				return rule.rule().maskType();
			}
		}
		// 2) 本次调用携带的规则
		for (FieldMaskRule rule : options.fieldRules()) {
			if (matcherFor(rule.fieldPath()).matches(path)) {
				return rule.maskType();
			}
		}
		return null;
	}

	/** 取（并缓存）某个字段路径对应的匹配器，避免在采集热点上重复编译。 */
	private FieldPathMatcher matcherFor(String fieldPath) {
		String key = fieldPath == null ? "" : fieldPath;
		FieldPathMatcher cached = matcherCache.get(key);
		if (cached != null) {
			return cached;
		}
		FieldPathMatcher compiled = FieldPathMatcher.compile(key);
		FieldPathMatcher existing = matcherCache.putIfAbsent(key, compiled);
		return existing != null ? existing : compiled;
	}

	/**
	 * 内容级脱敏。
	 *
	 * <p>优先走「合并预筛」路径（{@link RegexMaskGroup}）：一次扫描即可判定
	 * 是否需要逐条替换，避免对每个值跑 N 遍正则。
	 * 未由装配方固定规则时，退回按 {@code options.regexRules()} 逐次执行的兼容路径。
	 */
	private String applyRegexRules(String text, CaptureOptions options) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		if (regexGroup != null) {
			return regexGroup.mask(text, (matched, rule) -> {
				metrics.increment(AuditMetricNames.MASKED);
				return applyMask(matched, rule.maskType(), rule.keepPrefix(), rule.keepSuffix());
			});
		}
		String result = text;
		for (RegexMaskRule rule : options.regexRules()) {
			Matcher matcher = rule.pattern().matcher(result);
			StringBuilder builder = null;
			while (matcher.find()) {
				if (builder == null) {
					builder = new StringBuilder(result.length() + 16);
				}
				matcher.appendReplacement(builder,
					Matcher.quoteReplacement(applyMask(matcher.group(), rule.maskType(), rule.keepPrefix(),
						rule.keepSuffix())));
			}
			if (builder != null) {
				matcher.appendTail(builder);
				result = builder.toString();
				metrics.increment(AuditMetricNames.MASKED);
			}
		}
		return result;
	}

	private String applyMask(String value, MaskType type, CaptureOptions options) {
		return applyMask(value, type, 0, 0);
	}

	private String applyMask(String value, MaskType type, int keepPrefix, int keepSuffix) {
		if (value == null || value.isEmpty()) {
			return value;
		}
		return switch (type) {
			case FULL -> FULL_MASK;
			case REMOVE -> "";
			case HASH -> "sha256:" + sha256(value).substring(0, 16);
			case PARTIAL -> {
				int prefix = Math.max(0, Math.min(keepPrefix, value.length()));
				int suffix = Math.max(0, Math.min(keepSuffix, value.length() - prefix));
				StringBuilder builder = new StringBuilder();
				builder.append(value, 0, prefix);
				int maskedLength = value.length() - prefix - suffix;
				builder.append("*".repeat(Math.max(1, Math.min(maskedLength, 32))));
				if (suffix > 0) {
					builder.append(value, value.length() - suffix, value.length());
				}
				yield builder.toString();
			}
		};
	}

	/**
	 * 对请求头执行脱敏。
	 *
	 * <p>{@code headers} 为 {@code null} 或空时返回空 Map。
	 * {@link #MANDATORY_SENSITIVE_HEADERS} 中的头（大小写不敏感比较）整值替换为掩码，
	 * 不受自定义脱敏开关影响；其余头的值只做内容级正则脱敏。
	 *
	 * @param headers 请求头，键为原始书写形式
	 * @param options 本次采集的选项，提供调用方追加的敏感头名单
	 * @return 新建的 {@link LinkedHashMap}，保留入参的头名书写形式与迭代顺序
	 */
	@Override
	public Map<String, String> maskHeaders(Map<String, String> headers, CaptureOptions options) {
		if (headers == null || headers.isEmpty()) {
			return Map.of();
		}
		Map<String, String> masked = new LinkedHashMap<>();
		headers.forEach((name, value) -> {
			String lower = name.toLowerCase(Locale.ROOT);
			// 强制名单不受 customMaskingEnabled 影响：这是不可关闭的底线
			boolean mandatory = MANDATORY_SENSITIVE_HEADERS.contains(lower);
			boolean custom = customMaskingEnabled && options.sensitiveHeaders().contains(lower);
			if (mandatory || custom) {
				masked.put(name, FULL_MASK);
				metrics.increment(AuditMetricNames.MASKED);
			}
			else {
				masked.put(name, applyRegexRules(value == null ? "" : value, options));
			}
		});
		return masked;
	}

	/**
	 * 对查询串执行脱敏。
	 *
	 * <p>{@code queryString} 为 {@code null} 或空时原样返回；自定义脱敏关闭时同样原样返回。
	 * 仅当参数名命中敏感名单（强制头名单、调用方敏感头、字段规则末段名）时整值替换为掩码，
	 * 其余参数值只做内容级正则脱敏。
	 *
	 * @param queryString 原始查询串，形如 {@code a=1&token=xxx}
	 * @param options     本次采集的选项
	 * @return 脱敏后的查询串，参数分隔符与顺序保持不变
	 */
	@Override
	public String maskQueryString(String queryString, CaptureOptions options) {
		if (queryString == null || queryString.isEmpty()) {
			return queryString;
		}
		// 同 maskPayload：关闭自定义脱敏时查询串原样返回
		if (!customMaskingEnabled) {
			return queryString;
		}
		StringBuilder result = new StringBuilder(queryString.length());
		String[] pairs = queryString.split("&");
		for (int index = 0; index < pairs.length; index++) {
			if (index > 0) {
				result.append('&');
			}
			String pair = pairs[index];
			int equals = pair.indexOf('=');
			if (equals < 0) {
				result.append(pair);
				continue;
			}
			String name = pair.substring(0, equals);
			String value = pair.substring(equals + 1);
			if (isSensitiveName(name, options)) {
				result.append(name).append('=').append(FULL_MASK);
				metrics.increment(AuditMetricNames.MASKED);
			}
			else {
				result.append(name).append('=').append(applyRegexRules(value, options));
			}
		}
		return result.toString();
	}

	private boolean isSensitiveName(String name, CaptureOptions options) {
		String lower = name.toLowerCase(Locale.ROOT);
		if (MANDATORY_SENSITIVE_HEADERS.contains(lower)) {
			return true;
		}
		if (options.sensitiveHeaders().contains(lower)) {
			return true;
		}
		for (FieldMaskRule rule : options.fieldRules()) {
			if (fieldNameOf(rule.fieldPath()).equalsIgnoreCase(name)) {
				return true;
			}
		}
		for (CompiledFieldRule rule : compiledRules) {
			if (fieldNameOf(rule.rule().fieldPath()).equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	private static String fieldNameOf(String fieldPath) {
		String path = fieldPath.startsWith("$.") ? fieldPath.substring(2) : fieldPath;
		int dot = path.lastIndexOf('.');
		return dot >= 0 ? path.substring(dot + 1) : path;
	}

	private Payload rebuild(Payload original, String body, CaptureOptions options) {
		int max = options.maxBodyLength();
		boolean truncated = original.truncated() || body.length() > max;
		String finalBody = body.length() > max ? body.substring(0, max) + "...(truncated)" : body;
		long size = original.originalSize() > 0
			? original.originalSize()
			: finalBody.getBytes(StandardCharsets.UTF_8).length;
		return new Payload(original.contentType(), finalBody, size, truncated);
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(bytes.length * 2);
			for (byte b : bytes) {
				builder.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return builder.toString();
		}
		catch (NoSuchAlgorithmException error) {
			// SHA-256 不可用时退化为 32 位散列：仍不返回原文，但不具备抗碰撞性
			return Integer.toHexString(value.hashCode());
		}
	}

	/**
	 * 编译后的字段规则：原始规则 + 预编译的路径匹配器。
	 *
	 * <p>匹配器在实例构造期一次性编译并长期复用，避免在采集热点上重复编译 glob。
	 * 匹配语义见 {@link FieldPathMatcher}：不带 {@code **} 的规则命中任意前缀层级，
	 * 语义偏严会让已配置的敏感字段原文落库。
	 */
	private record CompiledFieldRule(FieldMaskRule rule, FieldPathMatcher matcher) {
	}
}
