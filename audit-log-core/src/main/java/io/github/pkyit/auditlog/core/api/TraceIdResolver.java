package io.github.pkyit.auditlog.core.api;

/**
 * 会话 / 链路标识解析 SPI。
 *
 * <p>缺少链路 ID 时，多步操作与跨服务调用的因果链无法重建，
 * 因此采集侧必须为每条记录提供链路标识。
 */
public interface TraceIdResolver {

	/**
	 * 解析链路 ID。
	 *
	 * <p>MUST NOT 抛异常；无法解析时返回 {@code null}，框架会回退生成一个内部标识。
	 */
	String resolveTraceId(PrincipalResolver.ResolutionContext context);

	/** 顺序，值越小越先执行。 */
	default int getOrder() {
		return 100;
	}
}
