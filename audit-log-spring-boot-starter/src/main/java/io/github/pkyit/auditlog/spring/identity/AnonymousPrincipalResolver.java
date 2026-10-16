package io.github.pkyit.auditlog.spring.identity;

import io.github.pkyit.auditlog.core.api.PrincipalInfo;
import io.github.pkyit.auditlog.core.api.PrincipalResolver;

import java.util.Optional;

/**
 * 兜底解析器：始终返回匿名主体，保证解析链总有结果。
 *
 * <p>匿名主体会被明确记录为 {@code ANONYMOUS}，而不是伪装成可信身份 ——
 * 审计消费方因此能区分「未认证访问」与「认证用户访问」。
 */
public class AnonymousPrincipalResolver implements PrincipalResolver {

	@Override
	public Optional<PrincipalInfo> resolve(ResolutionContext context) {
		return Optional.of(PrincipalInfo.anonymous());
	}

	@Override
	public int getOrder() {
		return Integer.MAX_VALUE;
	}
}
