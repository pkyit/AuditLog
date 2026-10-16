package io.github.pkyit.examples.erdemo.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 客户实体。
 *
 * @param id          主键；新建时为 {@code null}
 * @param name        客户名称
 * @param email       邮箱，属敏感字段
 * @param phone       手机号，属敏感字段
 * @param idCard      证件号，属敏感字段
 * @param creditLimit 授信额度
 * @param version     乐观锁版本号，每次更新递增
 * @param createdAt   创建时间
 */
public record Customer(
	Long id,
	String name,
	String email,
	String phone,
	String idCard,
	BigDecimal creditLimit,
	int version,
	Instant createdAt) {
}
