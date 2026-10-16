package io.github.pkyit.auditlog.samples.repo;

/**
 * 示例实体：用于验证事务提交 / 回滚与审计结果的一致性。
 */
public class DemoRecord {

	private Long id;

	private String name;

	public DemoRecord() {
	}

	public DemoRecord(String name) {
		this.name = name;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}
}
