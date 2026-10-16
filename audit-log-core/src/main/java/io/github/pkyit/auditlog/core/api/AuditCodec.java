package io.github.pkyit.auditlog.core.api;

import io.github.pkyit.auditlog.core.model.AuditRecord;

/**
 * 审计记录编解码 SPI。
 *
 * <p>独立于 {@link AuditMasker} 的存在理由：脱敏处理业务载荷内部的内容，
 * 编解码负责审计记录自身的线格式（落盘、发 MQ、写库的字节表示）。
 * 两者的生命周期与配置来源完全不同，合并为一个接口会使更换存储格式必须重写脱敏逻辑。
 */
public interface AuditCodec {

	/** 编码为一行文本（不含换行符）。 */
	String encode(AuditRecord record);

	/**
	 * 解码一行文本。
	 *
	 * @return 记录；无法解码时返回 {@code null}（调用方决定是丢弃还是死信）
	 */
	AuditRecord decode(String line);
}
