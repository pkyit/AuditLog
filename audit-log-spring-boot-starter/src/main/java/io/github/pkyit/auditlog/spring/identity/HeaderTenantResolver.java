package io.github.pkyit.auditlog.spring.identity;

import io.github.pkyit.auditlog.core.api.PrincipalResolver;
import io.github.pkyit.auditlog.core.api.TenantResolver;

import java.util.Optional;

/**
 * 从请求头解析租户标识。
 *
 * <p>多租户场景下审计数据必须可按租户隔离与导出，因此租户是一等字段。
 */
public class HeaderTenantResolver implements TenantResolver {

	private final String header;

	public HeaderTenantResolver(String header) {
		this.header = header;
	}

	@Override
	public Optional<String> resolve(PrincipalResolver.ResolutionContext context) {
		if (header == null || header.isBlank()) {
			return Optional.empty();
		}
		String value = context.getHeader(header);
		return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
	}
}
