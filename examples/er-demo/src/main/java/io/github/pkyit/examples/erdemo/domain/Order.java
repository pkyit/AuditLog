package io.github.pkyit.examples.erdemo.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单实体，通过 {@code customerId} 关联客户。
 *
 * @param id          主键；新建时为 {@code null}
 * @param orderNo     订单号，全局唯一
 * @param customerId  所属客户主键
 * @param status      订单状态
 * @param totalAmount 订单总额，由明细汇总得出
 * @param createdAt   创建时间
 */
public record Order(
	Long id,
	String orderNo,
	Long customerId,
	String status,
	BigDecimal totalAmount,
	Instant createdAt) {
}
