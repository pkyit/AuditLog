package io.github.pkyit.auditlog.core.mask;

import java.util.Objects;

/**
 * 字段名（路径）脱敏规则。
 *
 * <p>规范构造器对参数做归一化而不是拒绝：{@code fieldPath} 为 {@code null} 时抛出
 * {@link NullPointerException}；{@code maskType} 为 {@code null} 时取 {@link MaskType#FULL}，
 * {@code maskChar} 为 {@code 0} 时取 {@code '*'}，保留长度取负时按 {@code 0} 处理。
 * 实例字段因此恒为合法取值，调用方无需二次校验。
 *
 * <p>{@code fieldPath} 的语法不在本类校验，非法表达式交由
 * {@link FieldPathMatcher#compile(String)} 按退化规则处理，不抛异常。
 *
 * @param fieldPath  字段路径，支持 {@code $} 根、{@code *} 单层通配、{@code **} 多层通配、
 *                   {@code [*]} 数组通配，例如 {@code $.data[*].idCard}、{@code **.password}
 * @param maskType   脱敏策略，为 {@code null} 时归一化为 {@link MaskType#FULL}
 * @param maskChar   掩码字符，{@link MaskType#FULL} / {@link MaskType#PARTIAL} 使用；
 *                   为 {@code 0} 时归一化为 {@code '*'}
 * @param keepPrefix {@link MaskType#PARTIAL} 保留的前缀长度，负数按 {@code 0} 处理
 * @param keepSuffix {@link MaskType#PARTIAL} 保留的后缀长度，负数按 {@code 0} 处理
 */
public record FieldMaskRule(String fieldPath, MaskType maskType, char maskChar, int keepPrefix, int keepSuffix) {

	public FieldMaskRule {
		Objects.requireNonNull(fieldPath, "fieldPath must not be null");
		if (maskType == null) {
			maskType = MaskType.FULL;
		}
		if (maskChar == 0) {
			maskChar = '*';
		}
		if (keepPrefix < 0) {
			keepPrefix = 0;
		}
		if (keepSuffix < 0) {
			keepSuffix = 0;
		}
	}

	/**
	 * 创建整体掩码规则。
	 *
	 * @param fieldPath 目标字段路径，语法见 {@link FieldPathMatcher}
	 * @return 掩码字符为 {@code '*'}、策略为 {@link MaskType#FULL} 的规则
	 */
	public static FieldMaskRule full(String fieldPath) {
		return new FieldMaskRule(fieldPath, MaskType.FULL, '*', 0, 0);
	}

	/**
	 * 创建保留首尾的掩码规则。
	 *
	 * @param fieldPath  目标字段路径，语法见 {@link FieldPathMatcher}
	 * @param keepPrefix 保留的前缀长度，负数按 {@code 0} 处理
	 * @param keepSuffix 保留的后缀长度，负数按 {@code 0} 处理
	 * @return 掩码字符为 {@code '*'}、策略为 {@link MaskType#PARTIAL} 的规则
	 */
	public static FieldMaskRule partial(String fieldPath, int keepPrefix, int keepSuffix) {
		return new FieldMaskRule(fieldPath, MaskType.PARTIAL, '*', keepPrefix, keepSuffix);
	}

	/**
	 * 创建摘要替换规则。
	 *
	 * @param fieldPath 目标字段路径，语法见 {@link FieldPathMatcher}
	 * @return 策略为 {@link MaskType#HASH} 的规则
	 */
	public static FieldMaskRule hash(String fieldPath) {
		return new FieldMaskRule(fieldPath, MaskType.HASH, '*', 0, 0);
	}

	/**
	 * 创建字段删除规则。
	 *
	 * @param fieldPath 目标字段路径，语法见 {@link FieldPathMatcher}
	 * @return 策略为 {@link MaskType#REMOVE} 的规则
	 */
	public static FieldMaskRule remove(String fieldPath) {
		return new FieldMaskRule(fieldPath, MaskType.REMOVE, '*', 0, 0);
	}
}
