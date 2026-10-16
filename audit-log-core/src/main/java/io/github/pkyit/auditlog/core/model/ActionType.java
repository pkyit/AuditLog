package io.github.pkyit.auditlog.core.model;

/**
 * 审计动作类型。
 *
 * <p>审计查询与合规报表需要按动作分类聚合（例如「所有导出类操作」）。
 * 自由文本的操作名无法可靠分类，因此动作分类必须显式标注。
 */
public enum ActionType {

	/** 未分类（未显式标注时的兜底值）。 */
	UNKNOWN,

	/** 认证：登录、登出、令牌刷新。 */
	AUTHENTICATE,

	/** 授权：授权、取消授权、角色/权限变更。 */
	AUTHORIZE,

	/** 查询：只读操作。 */
	QUERY,

	/** 数据变更：新增、修改、删除。 */
	MUTATE,

	/** 数据导出：下载、导出、打印。 */
	EXPORT,

	/** 数据导入：上传、批量导入。 */
	IMPORT,

	/** 配置变更：系统配置、开关、参数。 */
	CONFIGURE,

	/** 管理动作：运维、启停、重置。 */
	ADMIN,

	/** 敏感数据访问：查看敏感信息、解密。 */
	SENSITIVE_ACCESS
}
