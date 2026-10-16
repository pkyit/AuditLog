package io.github.pkyit.examples.erdemo.web;

import io.github.pkyit.examples.erdemo.domain.Customer;
import io.github.pkyit.examples.erdemo.repo.CustomerRepository;
import io.github.pkyit.auditlog.core.model.ActionType;
import io.github.pkyit.auditlog.spring.annotation.AuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

/**
 * 客户接口。
 *
 * <p>所有写操作都带 {@code @AuditLog}：审计记录中的操作名、动作类型与资源标识
 * 由语义通道从注解解析；未标注的查询接口仅由边界通道兜底采集。
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

	/** 新建客户的请求体；证件号与手机号为敏感字段，落审计前应被掩码。 */
	public record CreateRequest(String name, String email, String phone, String idCard,
			BigDecimal creditLimit) {
	}

	private final CustomerRepository customers;

	public CustomerController(CustomerRepository customers) {
		this.customers = customers;
	}

	@AuditLog(name = "创建客户", actionType = ActionType.MUTATE,
		resourceType = "customer", resourceId = "#result.id", tags = { "domain=er" })
	@PostMapping
	public ResponseEntity<Customer> create(@RequestBody CreateRequest request) {
		Customer created = customers.insert(new Customer(null, request.name(), request.email(),
			request.phone(), request.idCard(),
			request.creditLimit() == null ? BigDecimal.ZERO : request.creditLimit(), 0, null));
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	/** 未标注注解：语义字段应为空，但边界通道仍须留下记录。 */
	@GetMapping("/{id}")
	public Customer get(@PathVariable Long id) {
		return customers.findById(id).orElseThrow(
			() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "客户不存在: " + id));
	}

	@GetMapping
	public List<Customer> list() {
		return customers.findAll();
	}

	@AuditLog(name = "更新客户", actionType = ActionType.MUTATE,
		resourceType = "customer", resourceIdParam = "id")
	@PutMapping("/{id}")
	public Customer update(@PathVariable Long id, @RequestBody CreateRequest request) {
		Customer existing = customers.findById(id).orElseThrow(
			() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "客户不存在: " + id));
		Customer updated = new Customer(existing.id(), request.name(), request.email(),
			request.phone(), request.idCard(),
			request.creditLimit() == null ? existing.creditLimit() : request.creditLimit(),
			existing.version(), existing.createdAt());
		// 乐观锁：版本不匹配说明存在并发修改，以 409 暴露而不是静默覆盖
		if (customers.update(updated) == 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "客户已被并发修改: " + id);
		}
		return customers.findById(id).orElseThrow();
	}

	@AuditLog(name = "删除客户", actionType = ActionType.MUTATE,
		resourceType = "customer", resourceIdParam = "id", sensitive = true)
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		// 客户仍有订单时由外键约束阻止删除，异常会以 500 暴露，用于验证失败结果采集
		customers.deleteById(id);
		return ResponseEntity.noContent().build();
	}
}
