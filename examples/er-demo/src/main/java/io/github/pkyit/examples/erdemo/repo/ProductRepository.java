package io.github.pkyit.examples.erdemo.repo;

import io.github.pkyit.examples.erdemo.domain.Product;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 商品数据访问。
 *
 * <p>库存扣减使用条件更新（{@code stock >= ?}）：库存不足时影响行数为 0，
 * 由服务层据此抛出异常触发事务回滚 —— 这是验证"审计结果与真实事务结果一致"的关键路径。
 */
@Repository
public class ProductRepository {

	private static final String COLUMNS = "id, sku, name, price, stock";

	private static final RowMapper<Product> MAPPER = (rs, rowNum) -> new Product(
		rs.getLong("id"),
		rs.getString("sku"),
		rs.getString("name"),
		rs.getBigDecimal("price"),
		rs.getInt("stock"));

	private final JdbcTemplate jdbc;

	public ProductRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Product insert(Product product) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO er_product (sku, name, price, stock) VALUES (?,?,?,?)",
				new String[] { "id" });
			ps.setString(1, product.sku());
			ps.setString(2, product.name());
			ps.setBigDecimal(3, product.price());
			ps.setInt(4, product.stock());
			return ps;
		}, keys);
		return findById(keys.getKey().longValue()).orElseThrow();
	}

	public Optional<Product> findById(Long id) {
		List<Product> found = jdbc.query(
			"SELECT " + COLUMNS + " FROM er_product WHERE id = ?", MAPPER, id);
		return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
	}

	public Optional<Product> findBySku(String sku) {
		List<Product> found = jdbc.query(
			"SELECT " + COLUMNS + " FROM er_product WHERE sku = ?", MAPPER, sku);
		return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
	}

	public List<Product> findAll() {
		return jdbc.query("SELECT " + COLUMNS + " FROM er_product ORDER BY id", MAPPER);
	}

	/** 扣减库存；库存不足时返回 0（不会更新成负数）。 */
	public int decrementStock(Long productId, int quantity) {
		return jdbc.update(
			"UPDATE er_product SET stock = stock - ? WHERE id = ? AND stock >= ?",
			quantity, productId, quantity);
	}

	/** 重置库存与价格，仅供验证脚本恢复数据使用。 */
	public int reset(Long productId, int stock) {
		return jdbc.update("UPDATE er_product SET stock = ? WHERE id = ?", stock, productId);
	}

	public long count() {
		Long value = jdbc.queryForObject("SELECT count(*) FROM er_product", Long.class);
		return value == null ? 0L : value;
	}
}
