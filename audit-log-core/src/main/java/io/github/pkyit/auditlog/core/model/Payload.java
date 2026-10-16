package io.github.pkyit.auditlog.core.model;

/**
 * 审计载荷（脱敏后的结构化快照）。
 *
 * <p>载荷必须是结构化字段，而不是 {@code toString()} 之类的非结构化转储：
 * 审计数据必须可索引、可检索、可回放。
 *
 * @param contentType 内容类型（{@code json} / {@code text} / {@code form} / {@code summarized}）
 * @param body        已脱敏、已截断的内容
 * @param originalSize 原始字节长度（截断前），{@code -1} 表示未知
 * @param truncated   是否被截断
 */
public record Payload(String contentType, String body, long originalSize, boolean truncated) {

	public static final Payload EMPTY = new Payload("none", null, 0L, false);

	public static Payload json(String body, long originalSize, boolean truncated) {
		return new Payload("json", body, originalSize, truncated);
	}

	public static Payload text(String body, long originalSize, boolean truncated) {
		return new Payload("text", body, originalSize, truncated);
	}

	public boolean isEmpty() {
		return body == null || body.isEmpty();
	}
}
