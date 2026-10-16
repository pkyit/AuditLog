package io.github.pkyit.examples.erdemo.domain;

import java.math.BigDecimal;

/**
 * 客户消费汇总，由客户与订单两级关联聚合得出。
 *
 * @param customerId   客户主键
 * @param customerName 客户名称
 * @param orderCount   订单数量
 * @param totalAmount  消费总额；无订单时为 {@code 0}
 */
public record CustomerSummary(
	Long customerId,
	String customerName,
	long orderCount,
	BigDecimal totalAmount) {
}
