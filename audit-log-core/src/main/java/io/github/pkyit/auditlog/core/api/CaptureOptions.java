package io.github.pkyit.auditlog.core.api;

import io.github.pkyit.auditlog.core.mask.FieldMaskRule;
import io.github.pkyit.auditlog.core.mask.RegexMaskRule;

import java.util.List;
import java.util.Set;

/**
 * 采集选项 —— 采集期的不可变配置快照。
 *
 * <p>由全局配置（{@code AuditLogProperties}）与方法级注解（{@code @AuditLog}）合并产生，
 * 保证同一请求内所有采集点使用一致的规则。
 *
 * @param captureRequestPayload  是否采集请求体
 * @param captureResponsePayload 是否采集响应体（默认关闭）
 * @param captureRequestHeaders  是否采集请求头
 * @param maxBodyLength          载荷最大字符数（超出截断）
 * @param maxDepth               序列化最大深度
 * @param includeStackTrace      异常是否记录堆栈（默认关闭）
 * @param maxErrorMessageLength  异常消息最大长度
 * @param fieldRules             字段名脱敏规则
 * @param regexRules             内容脱敏规则
 * @param sensitiveHeaders       需要脱敏的请求头（大小写不敏感，含不可被覆盖的强制项）
 * @param excludedHeaders        不采集的请求头
 */
public record CaptureOptions(
	boolean captureRequestPayload,
	boolean captureResponsePayload,
	boolean captureRequestHeaders,
	int maxBodyLength,
	int maxDepth,
	boolean includeStackTrace,
	int maxErrorMessageLength,
	List<FieldMaskRule> fieldRules,
	List<RegexMaskRule> regexRules,
	Set<String> sensitiveHeaders,
	Set<String> excludedHeaders) {

	/** 说明：此处不做防御性拷贝，调用方（框架内部）负责传入不可变集合。 */
	public CaptureOptions {
		if (maxBodyLength <= 0) {
			maxBodyLength = 4096;
		}
		if (maxDepth <= 0) {
			maxDepth = 8;
		}
		if (maxErrorMessageLength <= 0) {
			maxErrorMessageLength = 1024;
		}
		fieldRules = fieldRules == null ? List.of() : List.copyOf(fieldRules);
		regexRules = regexRules == null ? List.of() : List.copyOf(regexRules);
		sensitiveHeaders = sensitiveHeaders == null ? Set.of() : Set.copyOf(sensitiveHeaders);
		excludedHeaders = excludedHeaders == null ? Set.of() : Set.copyOf(excludedHeaders);
	}
}
