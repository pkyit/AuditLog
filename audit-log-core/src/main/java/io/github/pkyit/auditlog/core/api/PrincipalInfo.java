package io.github.pkyit.auditlog.core.api;

import io.github.pkyit.auditlog.core.model.PrincipalType;

import java.io.Serializable;

/**
 * 主体（操作者）解析结果。
 *
 * <p>不变量 I1：身份只能来自认证上下文。从请求头等客户端可控来源解析出的主体，
 * 必须使用 {@link PrincipalType#UNTRUSTED_HEADER} 标注，使审计消费方能够区分可信度；
 * 标注为可信的主体才会被消费方当作责任主体使用。
 */
public record PrincipalInfo(String id, String name, PrincipalType type, String authType, String sessionId)
	implements Serializable {

	private static final long serialVersionUID = 1L;

	/**
	 * 匿名主体。
	 *
	 * <p>身份字段全为 {@code null}，{@link #type()} 为 {@link PrincipalType#ANONYMOUS}，
	 * 调用方无需再判空即可安全落库。
	 */
	public static PrincipalInfo anonymous() {
		return new PrincipalInfo(null, null, PrincipalType.ANONYMOUS, null, null);
	}

	/**
	 * 已认证主体。
	 *
	 * @param id       认证上下文中的稳定主体标识
	 * @param name     展示名；无展示名时传 {@code null}
	 * @param authType 认证方式（如 {@code session} / {@code jwt}）；未知时传 {@code null}
	 */
	public static PrincipalInfo authenticated(String id, String name, String authType) {
		return new PrincipalInfo(id, name, PrincipalType.AUTHENTICATED, authType, null);
	}

	/**
	 * 按指定可信度构造主体，认证方式与会话 ID 留空。
	 *
	 * @param type 可信度来源；传 {@link PrincipalType#UNTRUSTED_HEADER} 时
	 *             必须确认该身份确实取自客户端可控来源
	 */
	public static PrincipalInfo of(String id, String name, PrincipalType type) {
		return new PrincipalInfo(id, name, type, null, null);
	}

	/**
	 * 身份是否来自可信来源。
	 *
	 * <p>仅 {@link PrincipalType#AUTHENTICATED} 与 {@link PrincipalType#INTERNAL} 为可信；
	 * {@link PrincipalType#UNTRUSTED_HEADER} 即使内容格式合法也不可信。
	 */
	public boolean isTrusted() {
		return type == PrincipalType.AUTHENTICATED || type == PrincipalType.INTERNAL;
	}
}
