package io.github.pkyit.auditlog.spring.collect;

import io.github.pkyit.auditlog.core.mask.FieldMaskRule;
import io.github.pkyit.auditlog.core.mask.RegexMaskRule;
import io.github.pkyit.auditlog.spring.method.AuditMethodMetadata;

import java.util.List;

/**
 * 单次请求的采集计划（全局配置 + 方法级注解合并后的结果）。
 *
 * <p>合并规则遵循「最小采集 + 安全优先」：
 * <ul>
 *   <li>未被 {@code @AuditLog} 标注的请求（仅边界通道覆盖）→ 不采集请求体/响应体/请求头，
 *       只记录来源、结果与耗时。这样既保证「不漏关键安全事件」，又不会让全站请求都付出
 *       序列化与脱敏开销；</li>
 *   <li>被标注的请求 → 按注解的三开关采集，并叠加方法级的额外敏感字段。</li>
 * </ul>
 *
 * @param annotate            是否命中 {@code @AuditLog}（决定采集深度）
 * @param captureRequestPayload  是否采集请求载荷
 * @param captureResponsePayload 是否采集响应载荷
 * @param captureHeaders      是否采集请求头
 * @param captureQueryString  是否采集查询串
 * @param metadata            方法级元数据（可为 {@code null}）
 * @param fieldRules          本次请求的字段脱敏规则
 * @param regexRules          本次请求的正则脱敏规则
 */
public record CapturePlan(
	boolean annotate,
	boolean captureRequestPayload,
	boolean captureResponsePayload,
	boolean captureHeaders,
	boolean captureQueryString,
	AuditMethodMetadata metadata,
	List<FieldMaskRule> fieldRules,
	List<RegexMaskRule> regexRules) {

	/** 仅边界采集的最小计划。 */
	public static CapturePlan boundaryOnly(boolean captureQueryString, boolean captureHeaders,
			List<FieldMaskRule> fieldRules, List<RegexMaskRule> regexRules) {
		return new CapturePlan(false, false, false, captureHeaders, captureQueryString, null, fieldRules, regexRules);
	}
}
