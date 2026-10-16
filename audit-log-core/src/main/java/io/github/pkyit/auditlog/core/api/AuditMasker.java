package io.github.pkyit.auditlog.core.api;

import io.github.pkyit.auditlog.core.mask.FieldMaskRule;
import io.github.pkyit.auditlog.core.model.Payload;

import java.util.Collection;
import java.util.Map;

/**
 * 脱敏 SPI。
 *
 * <p>框架在请求线程内调用本接口完成脱敏，进入队列的永远是已脱敏数据：
 * 既避免了异步线程访问已失效的请求对象，也使敏感数据不进入内存队列与本地 WAL。
 */
public interface AuditMasker {

	/**
	 * 对结构化载荷脱敏。
	 *
	 * @param payload 原始载荷（JSON 文本）
	 * @param options 采集选项（含字段规则与正则规则）
	 * @return 脱敏后的载荷
	 */
	Payload maskPayload(Payload payload, CaptureOptions options);

	/**
	 * 对请求头脱敏。
	 *
	 * <p>实现必须保证大小写不敏感匹配：HTTP 头名大小写不敏感，
	 * 若按小写名单逐字匹配，{@code Authorization} 一类头会原样落库造成凭据泄漏。
	 *
	 * @param headers 请求头（名 → 值）
	 * @param options 采集选项
	 * @return 脱敏后的请求头
	 */
	Map<String, String> maskHeaders(Map<String, String> headers, CaptureOptions options);

	/**
	 * 对查询串脱敏。
	 *
	 * @param queryString 原始查询串（形如 {@code a=1&password=x}）
	 * @param options     采集选项
	 * @return 脱敏后的查询串
	 */
	String maskQueryString(String queryString, CaptureOptions options);

	/**
	 * 针对给定字段名集合构造一份临时规则（供方法级注解覆盖使用）。
	 *
	 * @param fieldNames 需要整体脱敏的字段名；为 {@code null} 时返回空规则集
	 * @return 规则集；元素为整字段脱敏规则，与全局规则合并时不覆盖全局规则
	 */
	default Collection<FieldMaskRule> toFieldRules(Collection<String> fieldNames) {
		return fieldNames == null ? java.util.List.of() : fieldNames.stream().map(FieldMaskRule::full).toList();
	}
}
