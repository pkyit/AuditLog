package io.github.pkyit.examples.erdemo.repo;

import io.github.pkyit.examples.erdemo.domain.CustomerSummary;
import io.github.pkyit.examples.erdemo.domain.Order;
import io.github.pkyit.examples.erdemo.domain.OrderItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 订单与订单明细数据访问。
 *
 * <p>明细表通过外键同时指向订单与商品，用于验证两级关联查询与级联删除。
 */
@Repository
public class OrderRepository {

	private static final String ORDER_COLUMNS =
		"id, order_no, customer_id, status, total_amount, created_at";

	private static final RowMapper<Order> ORDER_MAPPER = (rs, rowNum) -> new Order(
		rs.getLong("id"),
		rs.getString("order_no"),
		rs.getLong("customer_id"),
		rs.getString("status"),
		rs.getBigDecimal("total_amount"),
		rs.getTimestamp("created_at").toInstant());

	private static final RowMapper<OrderItem> ITEM_MAPPER = (rs, rowNum) -> new OrderItem(
		rs.getLong("id"),
		rs.getLong("order_id"),
		rs.getLong("product_id"),
		rs.getInt("quantity"),
		rs.getBigDecimal("unit_price"));

	private final JdbcTemplate jdbc;

	public OrderRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Order insert(Order order) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO er_order (order_no, customer_id, status, total_amount) VALUES (?,?,?,?)",
				new String[] { "id" });
			ps.setString(1, order.orderNo());
			ps.setLong(2, order.customerId());
			ps.setString(3, order.status());
			ps.setBigDecimal(4, order.totalAmount());
			return ps;
		}, keys);
		return findById(keys.getKey().longValue()).orElseThrow();
	}

	public void insertItem(Long orderId, OrderItem item) {
		jdbc.update(
			"INSERT INTO er_order_item (order_id, product_id, quantity, unit_price) VALUES (?,?,?,?)",
			orderId, item.productId(), item.quantity(), item.unitPrice());
	}

	public Optional<Order> findById(Long id) {
		List<Order> found = jdbc.query(
			"SELECT " + ORDER_COLUMNS + " FROM er_order WHERE id = ?", ORDER_MAPPER, id);
		return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
	}

	public List<Order> findByCustomerId(Long customerId) {
		return jdbc.query("SELECT " + ORDER_COLUMNS
			+ " FROM er_order WHERE customer_id = ? ORDER BY id", ORDER_MAPPER, customerId);
	}

	public List<OrderItem> findItems(Long orderId) {
		return jdbc.query("SELECT id, order_id, product_id, quantity, unit_price "
			+ "FROM er_order_item WHERE order_id = ? ORDER BY id", ITEM_MAPPER, orderId);
	}

	public int deleteById(Long id) {
		return jdbc.update("DELETE FROM er_order WHERE id = ?", id);
	}

	/**
	 * 客户消费汇总：客户左连接订单并聚合。
	 *
	 * <p>使用 {@code LEFT JOIN} 使无订单的客户也出现在结果中且金额为 0 ——
	 * 用于验证"关联查询结果被正确采集"而非静默丢行。
	 */
	public List<CustomerSummary> summarizeByCustomer() {
		return jdbc.query("""
			SELECT c.id AS customer_id,
			       c.name AS customer_name,
			       count(o.id) AS order_count,
			       coalesce(sum(o.total_amount), 0) AS total_amount
			FROM er_customer c
			LEFT JOIN er_order o ON o.customer_id = c.id
			GROUP BY c.id, c.name
			ORDER BY c.id
			""", (rs, rowNum) -> new CustomerSummary(
			rs.getLong("customer_id"),
			rs.getString("customer_name"),
			rs.getLong("order_count"),
			rs.getBigDecimal("total_amount")));
	}

	public long count() {
		Long value = jdbc.queryForObject("SELECT count(*) FROM er_order", Long.class);
		return value == null ? 0L : value;
	}

	public long countItems() {
		Long value = jdbc.queryForObject("SELECT count(*) FROM er_order_item", Long.class);
		return value == null ? 0L : value;
	}
}
