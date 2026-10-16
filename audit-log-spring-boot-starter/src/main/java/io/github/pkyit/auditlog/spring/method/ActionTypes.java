package io.github.pkyit.auditlog.spring.method;

import io.github.pkyit.auditlog.core.model.ActionType;

import java.util.Locale;

/**
 * 动作类型解析工具，Web 环境无关。
 *
 * <p>不得依赖 Servlet 类型：配置字符串到 {@link ActionType} 的换算与 Web 容器无关，
 * 一旦放入 servlet 专属类，Reactive 环境将无法复用。
 */
public final class ActionTypes {

	private ActionTypes() {
	}

	/**
	 * 解析配置里的动作类型字符串。
	 *
	 * <p>空值、空白值与非法值回退为 {@link ActionType#UNKNOWN}，不抛异常；大小写不敏感。
	 *
	 * @param configured 配置项 {@code collector.method.default-action-type} 的取值，允许为 {@code null}
	 * @return 解析结果，无法解析时为 {@link ActionType#UNKNOWN}
	 */
	public static ActionType parse(String configured) {
		if (configured == null || configured.isBlank()) {
			return ActionType.UNKNOWN;
		}
		try {
			return ActionType.valueOf(configured.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ignored) {
			// 非法配置不得中断审计，回退为 UNKNOWN，由启动校验负责告警
			return ActionType.UNKNOWN;
		}
	}
}
