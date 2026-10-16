package io.github.pkyit.auditlog.core.model;

/**
 * 业务操作结果，与 HTTP 状态码解耦。
 *
 * <p>许多企业系统返回 HTTP 200 + 业务错误码。只看 HTTP 状态码无法判定成败，
 * 因此审计记录同时保留「业务码」与「业务消息」。
 *
 * @param code    业务返回码，{@code null} 表示无业务码（纯 HTTP 语义）
 * @param message 业务消息摘要（已脱敏、已截断）
 * @param success 业务是否成功
 */
public record BusinessResult(Integer code, String message, boolean success) {

	/** 空业务结果。 */
	public static final BusinessResult EMPTY = new BusinessResult(null, null, true);

	public static BusinessResult of(Integer code, String message, boolean success) {
		return new BusinessResult(code, message, success);
	}
}
