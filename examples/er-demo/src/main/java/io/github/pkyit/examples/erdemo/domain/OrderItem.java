package io.github.pkyit.examples.erdemo.domain;

import java.math.BigDecimal;

/**
 * 订单明细，同时关联订单与商品。
 *
 * @param id        主键
 * @param orderId   所属订单主键
 * @param productId 商品主键
 * @param quantity  数量
 * @param unitPrice 下单时的单价快照
 */
public record OrderItem(
	Long id,
	Long orderId,
	Long productId,
	int quantity,
	BigDecimal unitPrice) {
}
