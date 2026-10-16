package io.github.pkyit.examples.erdemo.repo;

import io.github.pkyit.examples.erdemo.domain.Customer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 客户数据访问。
 *
 * <p>更新使用乐观锁（{@code version} 匹配才生效），用于验证"业务事务失败时审计记录如何判定结果"。
 */
@Repository
public class CustomerRepository {

	private static final String COLUMNS =
		"id, name, email, phone, id_card, credit_limit, version, created_at";

	private static final RowMapper<Customer> MAPPER = (rs, rowNum) -> new Customer(
		rs.getLong("id"),
		rs.getString("name"),
		rs.getString("email"),
		rs.getString("phone"),
		rs.getString("id_card"),
		rs.getBigDecimal("credit_limit"),
		rs.getInt("version"),
		rs.getTimestamp("created_at").toInstant());

	private final JdbcTemplate jdbc;

	public CustomerRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 插入客户并回填生成的主键。 */
	public Customer insert(Customer customer) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO er_customer (name, email, phone, id_card, credit_limit, version) "
					+ "VALUES (?,?,?,?,?,0)", new String[] { "id" });
			ps.setString(1, customer.name());
			ps.setString(2, customer.email());
			ps.setString(3, customer.phone());
			ps.setString(4, customer.idCard());
			ps.setBigDecimal(5, customer.creditLimit());
			return ps;
		}, keys);
		return findById(keys.getKey().longValue()).orElseThrow();
	}

	public Optional<Customer> findById(Long id) {
		List<Customer> found = jdbc.query(
			"SELECT " + COLUMNS + " FROM er_customer WHERE id = ?", MAPPER, id);
		return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
	}

	public List<Customer> findAll() {
		return jdbc.query("SELECT " + COLUMNS + " FROM er_customer ORDER BY id", MAPPER);
	}

	/** 乐观锁更新：版本不匹配返回 0，由调用方判定为并发冲突。 */
	public int update(Customer customer) {
		return jdbc.update(
			"UPDATE er_customer SET name=?, email=?, phone=?, id_card=?, credit_limit=?, "
				+ "version = version + 1 WHERE id=? AND version=?",
			customer.name(), customer.email(), customer.phone(), customer.idCard(),
			customer.creditLimit(), customer.id(), customer.version());
	}

	public int deleteById(Long id) {
		return jdbc.update("DELETE FROM er_customer WHERE id = ?", id);
	}

	public long count() {
		Long value = jdbc.queryForObject("SELECT count(*) FROM er_customer", Long.class);
		return value == null ? 0L : value;
	}
}
