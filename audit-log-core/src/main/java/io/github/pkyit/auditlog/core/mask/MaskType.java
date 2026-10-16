package io.github.pkyit.auditlog.core.mask;

/**
 * 脱敏策略。
 */
public enum MaskType {

	/** 整体替换为固定掩码（默认）。 */
	FULL,

	/** 保留首尾少量字符，中间掩码，例如 {@code 138****8888}。 */
	PARTIAL,

	/** 以不可逆摘要替换（保留可关联性但不泄漏原文）。 */
	HASH,

	/** 删除该字段。 */
	REMOVE
}
