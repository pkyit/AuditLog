package io.github.pkyit.auditlog.spring.identity;

import io.github.pkyit.auditlog.core.api.PrincipalInfo;
import io.github.pkyit.auditlog.core.api.PrincipalResolver;
import io.github.pkyit.auditlog.core.model.PrincipalType;

import java.util.Optional;

/**
 * 从请求头解析主体，默认关闭。
 *
 * <p>适用场景：应用部署在可信网关之后，网关已完成认证并以请求头下发身份，
 * 而应用自身没有 Spring Security 上下文（例如内部服务、遗留系统）。
 *
 * <p>安全约束：
 * <ul>
 *   <li>必须显式开启（{@code audit.log.identity.header-principal-enabled=true}），
 *       否则本解析器不注册；</li>
 *   <li>解析出的主体标记为 {@link PrincipalType#TRUSTED_HEADER}，
 *       使审计消费方明确知道该身份未经本应用认证，其可信度取决于网关；</li>
 *   <li>开启时必须配合网络层限制（只允许网关访问），框架无法代为保证。</li>
 * </ul>
 */
public class HeaderPrincipalResolver implements PrincipalResolver {

	private final String idHeader;
	private final String nameHeader;
	private final PrincipalType type;

	public HeaderPrincipalResolver(String idHeader, String nameHeader) {
		this(idHeader, nameHeader, PrincipalType.TRUSTED_HEADER);
	}

	public HeaderPrincipalResolver(String idHeader, String nameHeader, PrincipalType type) {
		this.idHeader = idHeader;
		this.nameHeader = nameHeader;
		this.type = type;
	}

	@Override
	public Optional<PrincipalInfo> resolve(ResolutionContext context) {
		String id = context.getHeader(idHeader);
		if (id == null || id.isBlank()) {
			return Optional.empty();
		}
		String name = context.getHeader(nameHeader);
		return Optional.of(new PrincipalInfo(id.trim(), name == null ? null : name.trim(), type, "header", null));
	}

	@Override
	public int getOrder() {
		// 低于认证上下文：只要认证上下文可用，就不会走请求头路径
		return 50;
	}
}
