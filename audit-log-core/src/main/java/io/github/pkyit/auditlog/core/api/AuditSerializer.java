package io.github.pkyit.auditlog.core.api;

import io.github.pkyit.auditlog.core.model.Payload;

import java.nio.charset.StandardCharsets;

/**
 * 序列化 SPI。
 *
 * <p>采集必须产生结构化、可检索的快照，非结构化的 {@code toString()} 转储无法用于检索与回放；
 * 且必须限制大小与深度，防止单个超大对象拖垮采集链路。
 */
public interface AuditSerializer {

	/**
	 * 将任意对象序列化为载荷。
	 *
	 * <p>实现 MUST NOT 抛异常：序列化失败时必须降级为「摘要占位符」载荷。
	 * 单个字段不可序列化不得导致整条审计记录丢失，更不得把异常传播到业务流程。
	 *
	 * @param value   待序列化对象
	 * @param options 采集选项
	 * @param label   用途标签（如 {@code request} / {@code response} / {@code argument}），用于降级占位符
	 * @return 载荷
	 */
	Payload serialize(Object value, CaptureOptions options, String label);

	/**
	 * 将已存在的文本（如缓存的请求体）包装为载荷，并做截断。
	 *
	 * @param text        原始文本；{@code null} 或空串返回 {@link Payload#EMPTY}
	 * @param contentType 载荷声明的 MIME 类型；未知时传 {@code null}
	 * @return 载荷；{@code size} 按 UTF-8 字节数计，超过 {@link CaptureOptions#maxBodyLength()} 时置截断标记
	 */
	default Payload wrapText(String text, CaptureOptions options, String contentType) {
		if (text == null || text.isEmpty()) {
			return Payload.EMPTY;
		}
		long size = text.getBytes(StandardCharsets.UTF_8).length;
		boolean truncated = text.length() > options.maxBodyLength();
		String body = truncated ? text.substring(0, options.maxBodyLength()) : text;
		return new Payload(contentType, body, size, truncated);
	}
}
