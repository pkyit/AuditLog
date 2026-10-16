package io.github.pkyit.auditlog.core.api;

import java.util.Optional;

/**
 * 租户解析 SPI。
 *
 * <p>多租户场景下审计数据必须可按租户隔离查询与导出，因此租户标识是一等字段。
 */
@FunctionalInterface
public interface TenantResolver {

	/**
	 * 解析当前请求的租户标识。
	 *
	 * @return 租户标识；{@link Optional#empty()} 表示无法确定，框架按未归属租户处理
	 */
	Optional<String> resolve(PrincipalResolver.ResolutionContext context);

	/** 顺序，值越小越先执行。 */
	default int getOrder() {
		return 100;
	}
}
