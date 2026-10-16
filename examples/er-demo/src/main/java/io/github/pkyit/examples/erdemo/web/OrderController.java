package io.github.pkyit.examples.erdemo.web;

import io.github.pkyit.examples.erdemo.domain.Order;
import io.github.pkyit.examples.erdemo.service.OrderService;
import io.github.pkyit.auditlog.core.model.ActionType;
import io.github.pkyit.auditlog.spring.annotation.AuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 订单接口，覆盖跨表事务的三条路径：成功提交、业务异常回滚、资源不存在。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

	/** 下单请求体。 */
	public record CreateRequest(Long customerId, List<OrderService.Line> lines) {
	}

	private final OrderService orders;

	public OrderController(OrderService orders) {
		this.orders = orders;
	}

	/**
	 * 下单。
	 *
	 * <p>库存不足时服务层抛异常，事务回滚；该请求的审计结果应为 {@code FAILURE}
	 * 或按配置留档为回滚，而不是成功。
	 */
	@AuditLog(name = "创建订单", actionType = ActionType.MUTATE,
		resourceType = "order", resourceId = "#result.id", logRequestParams = true,
		tags = { "domain=er", "biz=order" })
	@PostMapping
	public ResponseEntity<Order> create(@RequestBody CreateRequest request) {
		Order created = orders.create(request.customerId(), request.lines());
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@GetMapping("/{id}")
	public Map<String, Object> detail(@PathVariable Long id) {
		Map<String, Object> detail = orders.detail(id);
		if (detail == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在: " + id);
		}
		return detail;
	}

	@AuditLog(name = "删除订单", actionType = ActionType.MUTATE,
		resourceType = "order", resourceIdParam = "id")
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		if (!orders.delete(id)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在: " + id);
		}
		return ResponseEntity.noContent().build();
	}
}
