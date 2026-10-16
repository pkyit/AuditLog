package io.github.pkyit.auditlog.core.model;

/**
 * 主体（操作者）信息来源。
 *
 * <p>审计不变量 I1「身份不可伪造」要求记录身份的**来源**：
 * 只有来源为认证上下文的身份才具备不可否认性；从请求头读取的身份必须被显式标记为不可信。
 */
public enum PrincipalType {

	/** 来自认证上下文（Spring Security / 自定义认证），可信。 */
	AUTHENTICATED,

	/** 来自受信任的请求头（需显式开启，且应位于可信网关之后）。 */
	TRUSTED_HEADER,

	/** 来自不可信请求头（默认关闭，开启时审计记录会标注为不可信）。 */
	UNTRUSTED_HEADER,

	/** 来自应用内部调用（非 HTTP 入口）。 */
	INTERNAL,

	/** 匿名（未认证）。 */
	ANONYMOUS
}
