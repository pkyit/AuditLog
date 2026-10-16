package io.github.pkyit.auditlog.spring.identity;

import io.github.pkyit.auditlog.core.api.PrincipalResolver;
import io.github.pkyit.auditlog.core.api.TraceIdResolver;

/**
 * 从请求头解析链路 ID。
 *
 * <p>支持 W3C Trace Context（{@code traceparent: 00-<trace-id>-<span-id>-<flags>}）
 * 与常见的 {@code X-Trace-Id}。链路 ID 是重建「跨服务因果链」的唯一依据：缺少该字段时，
 * 多步操作与跨服务调用无法串联。两个请求头都不可用时返回 {@code null}，
 * 由框架回退生成内部标识。
 */
public class HeaderTraceIdResolver implements TraceIdResolver {

	private final String traceParentHeader;
	private final String fallbackHeader;

	public HeaderTraceIdResolver(String traceParentHeader, String fallbackHeader) {
		this.traceParentHeader = traceParentHeader;
		this.fallbackHeader = fallbackHeader;
	}

	@Override
	public String resolveTraceId(PrincipalResolver.ResolutionContext context) {
		String traceParent = context.getHeader(traceParentHeader);
		if (traceParent != null && !traceParent.isBlank()) {
			String[] parts = traceParent.split("-");
			// W3C 格式：version-traceid-spanid-flags
			if (parts.length >= 2 && parts[1].length() == 32 && !parts[1].chars().allMatch(ch -> ch == '0')) {
				return parts[1];
			}
		}
		String fallback = context.getHeader(fallbackHeader);
		if (fallback != null && !fallback.isBlank()) {
			return fallback.trim();
		}
		return null;
	}
}
