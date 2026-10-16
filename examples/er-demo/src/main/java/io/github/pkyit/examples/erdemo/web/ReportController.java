package io.github.pkyit.examples.erdemo.web;

import io.github.pkyit.examples.erdemo.domain.CustomerSummary;
import io.github.pkyit.examples.erdemo.domain.Product;
import io.github.pkyit.examples.erdemo.repo.CustomerRepository;
import io.github.pkyit.examples.erdemo.repo.OrderRepository;
import io.github.pkyit.examples.erdemo.repo.ProductRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表与运维接口。
 *
 * <p>这些接口**刻意不加** {@code @AuditLog}：它们是只读的诊断入口，
 * 用于验证"未标注注解的接口只被边界通道采集、语义字段为空"这一预期行为。
 */
@RestController
@RequestMapping("/api")
public class ReportController {

	private final CustomerRepository customerRepository;
	private final ProductRepository productRepository;
	private final OrderRepository orderRepository;

	public ReportController(CustomerRepository customerRepository, ProductRepository productRepository,
			OrderRepository orderRepository) {
		this.customerRepository = customerRepository;
		this.productRepository = productRepository;
		this.orderRepository = orderRepository;
	}

	/** 客户消费汇总：客户左连接订单后聚合。 */
	@GetMapping("/reports/customer-summary")
	public List<CustomerSummary> customerSummary() {
		return orderRepository.summarizeByCustomer();
	}

	/** 各表行数，供验证脚本核对事务是否真的回滚。 */
	@GetMapping("/ops/counts")
	public Map<String, Object> counts() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("customers", customerRepository.count());
		result.put("products", productRepository.count());
		result.put("orders", orderRepository.count());
		result.put("orderItems", orderRepository.countItems());
		return result;
	}

	/** 商品列表：验证脚本借此取得商品主键，也用于观察库存变化。 */
	@GetMapping("/products")
	public List<Product> listProducts() {
		return productRepository.findAll();
	}
}
