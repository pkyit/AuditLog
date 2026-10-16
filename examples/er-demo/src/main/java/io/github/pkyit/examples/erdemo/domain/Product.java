package io.github.pkyit.examples.erdemo.domain;

import java.math.BigDecimal;

/**
 * 商品实体。
 *
 * @param id    主键；新建时为 {@code null}
 * @param sku   商品编码，全局唯一
 * @param name  商品名称
 * @param price 单价
 * @param stock 库存数量，下单时扣减
 */
public record Product(
	Long id,
	String sku,
	String name,
	BigDecimal price,
	int stock) {
}
