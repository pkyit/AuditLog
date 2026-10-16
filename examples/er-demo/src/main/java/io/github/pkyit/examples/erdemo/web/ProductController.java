package io.github.pkyit.examples.erdemo.web;

import io.github.pkyit.examples.erdemo.domain.Product;
import io.github.pkyit.examples.erdemo.repo.ProductRepository;
import io.github.pkyit.auditlog.core.model.ActionType;
import io.github.pkyit.auditlog.spring.annotation.AuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 商品接口。
 *
 * <p>SKU 上有唯一约束，重复创建会触发数据库约束异常，
 * 用于验证"业务失败被如实记为失败"以及异常处理链路。
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

	/** 新建商品的请求体。 */
	public record CreateRequest(String sku, String name, BigDecimal price, Integer stock) {
	}

	private final ProductRepository products;

	public ProductController(ProductRepository products) {
		this.products = products;
	}

	@AuditLog(name = "创建商品", actionType = ActionType.MUTATE,
		resourceType = "product", tags = { "domain=er" })
	@PostMapping
	public ResponseEntity<Product> create(@RequestBody CreateRequest request) {
		if (request.sku() == null || request.sku().isBlank()) {
			throw new IllegalArgumentException("sku 不能为空");
		}
		Product created = products.insert(new Product(null, request.sku(), request.name(),
			request.price() == null ? BigDecimal.ZERO : request.price(),
			request.stock() == null ? 0 : request.stock()));
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}
}
