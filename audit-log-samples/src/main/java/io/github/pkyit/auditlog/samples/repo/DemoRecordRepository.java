package io.github.pkyit.auditlog.samples.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 示例仓储：以 {@code JdbcTemplate} 直接读写 {@code demo_record} 表。
 *
 * <p>示例验证的是事务边界与审计结果的一致性，与持久化框架无关，因此不引入额外 starter。
 * 本类不声明事务：提交或回滚由调用方的事务边界决定，脱离事务调用时每条语句按 JDBC
 * auto-commit 立即生效。
 */
@Repository
public class DemoRecordRepository {

	private final JdbcTemplate jdbcTemplate;

	public DemoRecordRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * 插入一条记录。
	 *
	 * <p>插入语句只使用 {@code name}，主键由数据库生成：入参上已设置的 {@code id} 被忽略，
	 * 生成的主键也不回填，方法返回后 {@code record.getId()} 仍为 {@code null}。
	 *
	 * @param record 待插入记录，其 {@code name} 不得为 {@code null}，否则违反 {@code name} 列的
	 *               {@code NOT NULL} 约束
	 * @throws org.springframework.dao.DataAccessException 语句执行失败时抛出，由 Spring 对
	 *         {@code SQLException} 转换得到
	 */
	public void save(DemoRecord record) {
		jdbcTemplate.update("INSERT INTO demo_record (name) VALUES (?)", record.getName());
	}

	/**
	 * 统计 {@code demo_record} 表的当前行数。
	 *
	 * @return 行数；驱动返回 {@code null} 时回退为 {@code 0}，不抛异常
	 */
	public long count() {
		Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo_record", Long.class);
		return count == null ? 0L : count;
	}
}
