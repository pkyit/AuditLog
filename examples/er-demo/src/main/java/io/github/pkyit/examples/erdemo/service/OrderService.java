package io.github.pkyit.examples.erdemo.service;

import io.github.pkyit.examples.erdemo.domain.Order;
import io.github.pkyit.examples.erdemo.domain.OrderItem;
import io.github.pkyit.examples.erdemo.domain.Product;
import io.github.pkyit.examples.erdemo.repo.OrderRepository;
import io.github.pkyit.examples.erdemo.repo.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 下单服务。
 *
 * <p>下单跨越订单与库存两张表，必须在同一事务内完成：任一步失败都要整体回滚，
 * 不能出现"订单已建但库存未扣"或"库存已扣但订单未建"。
 *
 * <p>该场景用于验证审计框架的事务对齐能力：回滚的请求应被如实标记为
 * {@code ROLLBACK} 并按配置决定是否留档，而不是记成成功。
 */
@Service
public class OrderService {

	/** 请求中的一行明细。 */
	public record Line(Long productId, int quantity) {
	}

	private final OrderRepository orders;
	private final ProductRepository products;

	public OrderService(OrderRepository orders, ProductRepository products) {
		this.orders = orders;
		this.products = products;
	}

	/**
	 * 创建订单。
	 *
	 * @param customerId 客户主键
	 * @param lines      明细行，不能为空
	 * @return 已落库的订单
	 * @throws IllegalArgumentException 明细为空、数量非正、商品不存在或库存不足
	 */
	@Transactional
	public Order create(Long customerId, List<Line> lines) {
		if (lines == null || lines.isEmpty()) {
			throw new IllegalArgumentException("订单明细不能为空");
		}
		List<OrderItem> items = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;
		for (Line line : lines) {
			if (line.quantity() <= 0) {
				throw new IllegalArgumentException("明细数量必须为正数：" + line.quantity());
			}
			Product product = products.findById(line.productId())
				.orElseThrow(() -> new IllegalArgumentException("商品不存在：" + line.productId()));
			// 条件更新：库存不足时影响行数为 0，此处主动抛异常触发整单回滚
			if (products.decrementStock(product.id(), line.quantity()) == 0) {
				throw new IllegalStateException(
					"库存不足：sku=" + product.sku() + " 需要=" + line.quantity()
						+ " 现有=" + product.stock());
			}
			items.add(new OrderItem(null, null, product.id(), line.quantity(), product.price()));
			total = total.add(product.price().multiply(BigDecimal.valueOf(line.quantity())));
		}

		Order order = orders.insert(new Order(null, nextOrderNo(), customerId, "CREATED", total, null));
		for (OrderItem item : items) {
			orders.insertItem(order.id(), item);
		}
		return order;
	}

	/** 按主键查询订单及其明细；订单不存在返回 {@code null}。 */
	public Map<String, Object> detail(Long orderId) {
		return orders.findById(orderId)
			.map(order -> Map.<String, Object>of(
				"order", order,
				"items", orders.findItems(orderId)))
			.orElse(null);
	}

	/** 删除订单；明细由数据库外键级联删除。 */
	@Transactional
	public boolean delete(Long orderId) {
		return orders.deleteById(orderId) > 0;
	}

	/** 生成订单号；时间戳加随机后缀，冲突概率可忽略。 */
	private static String nextOrderNo() {
		return "SO" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
	}
}
