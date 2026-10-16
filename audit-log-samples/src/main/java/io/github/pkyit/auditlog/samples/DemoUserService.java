package io.github.pkyit.auditlog.samples;

import io.github.pkyit.auditlog.samples.repo.DemoRecord;
import io.github.pkyit.auditlog.samples.repo.DemoRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 示例业务服务：验证审计结果与事务边界对齐。
 *
 * <p>两个方法构成对照：
 * <ul>
 *   <li>{@link #insertThenCommit} —— 事务提交成功，必须产生 {@code outcome=SUCCESS} 的审计记录；</li>
 *   <li>{@link #insertThenRollback} —— 事务必然回滚，默认策略下不得产生「成功」记录，
 *       否则审计结论与数据库的实际状态相反。</li>
 * </ul>
 */
@Service
public class DemoUserService {

	private final DemoRecordRepository repository;

	/**
	 * 注入示例记录仓储。
	 *
	 * @param repository 示例表仓储
	 */
	public DemoUserService(DemoRecordRepository repository) {
		this.repository = repository;
	}

	/**
	 * 插入一条记录并提交事务。
	 *
	 * @param name 记录名，同时作为返回值
	 * @return 传入的 {@code name}
	 */
	@Transactional
	public String insertThenCommit(String name) {
		repository.save(new DemoRecord(name));
		return name;
	}

	/** 先插入再抛异常：事务回滚，数据库与审计都不得留下「成功」痕迹。 */
	@Transactional
	public String insertThenRollback(String name) {
		repository.save(new DemoRecord(name));
		throw new IllegalStateException("模拟业务失败，事务将回滚");
	}

	/** 示例表当前记录数，用于断言回滚后没有落库。 */
	public long countRecords() {
		return repository.count();
	}
}
