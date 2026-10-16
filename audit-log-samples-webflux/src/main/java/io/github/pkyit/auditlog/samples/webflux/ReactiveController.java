package io.github.pkyit.auditlog.samples.webflux;

import io.github.pkyit.auditlog.core.model.ActionType;
import io.github.pkyit.auditlog.spring.annotation.AuditLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebFlux 控制器：既包含普通返回，也包含流式返回（{@code Flux} / SSE）。
 *
 * <p>流式返回是反应式特有的形态，审计采集必须正确处理"响应何时算结束"：
 * {@code Flux} 可能在订阅后才逐个产出，收尾时机与 Servlet 的同步响应完全不同。
 */
@RestController
@RequestMapping("/reactive")
public class ReactiveController {

	/** 普通返回（{@code Mono}）：结果在订阅时立即产出，响应一次写完。 */
	@GetMapping("/plain")
	public Mono<Map<String, Object>> plain() {
		return Mono.just(body("plain"));
	}

	/** 延迟给定毫秒后返回：延迟期间不产出元素，用于对照审计耗时与真实响应时长。 */
	@GetMapping("/slow/{millis}")
	public Mono<Map<String, Object>> slow(@PathVariable long millis) {
		return Mono.delay(Duration.ofMillis(millis)).map(ignored -> body("slow"));
	}

	/** 错误返回：异常在订阅时沿链路传播而非由响应体承载，用于覆盖异常路径的审计语义。 */
	@GetMapping("/error")
	public Mono<Map<String, Object>> error() {
		return Mono.error(new IllegalStateException("反应式链路内部失败"));
	}

	/** POST + 请求体（边界通道）：请求体可选，{@code payload} 为 {@code null} 表示未提交请求体。 */
	@PostMapping("/post")
	public Mono<Map<String, Object>> post(@RequestBody(required = false) Map<String, Object> payload) {
		Map<String, Object> result = body("post");
		result.put("receivedKeys", payload == null ? java.util.List.of() : payload.keySet());
		// 回显 orderId 的值本身：审计旁路拷贝请求体时若提前消费了 DataBuffer，
		// 字段值会丢失而 key 集合仍完整，仅回显 key 集合不足以暴露该问题。
		result.put("echoedOrderId", payload == null ? null : payload.get("orderId"));
		return Mono.just(result);
	}

	/**
	 * 流式返回（SSE/Flux）：响应分多次产出，审计只能在流终止后收尾，
	 * 不能按首次写出判定响应结束。
	 */
	@GetMapping(value = "/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<Map<String, Object>> stream() {
		return Flux.range(1, 3)
			.delayElements(Duration.ofMillis(80))
			.map(index -> {
				Map<String, Object> item = body("stream-" + index);
				item.put("index", index);
				return item;
			});
	}

	/**
	 * 语义通道端点：操作名、动作类型与资源标识由 {@link AuditLog} 注解提供。
	 *
	 * <p>边界通道只保证产生记录，业务含义由语义通道填充。注解解析先取方法级注解，
	 * 缺失时回退到声明类上的注解；{@code resourceIdParam = "orderId"} 决定资源标识
	 * 取自名为 {@code orderId} 的查询参数。
	 */
	@AuditLog(name = "创建反应式订单", actionType = ActionType.MUTATE,
		resourceType = "reactive-order", resourceIdParam = "orderId", tags = { "biz=webflux" })
	@PostMapping("/annotated")
	public Mono<Map<String, Object>> annotated(@RequestParam(required = false) String orderId,
			org.springframework.web.server.ServerWebExchange exchange) {
		Map<String, Object> result = body("annotated");
		result.put("orderId", orderId);
		// 回显 ServerWebExchange 解析到的查询参数：与业务方法收到的 orderId 比对，
		// 可区分"参数未到达"与"审计侧未解析"。
		result.put("exchangeSeesOrderId", exchange.getRequest().getQueryParams().getFirst("orderId"));
		return Mono.just(result);
	}

	/** 未标注注解的端点：应仅由边界通道兜底采集（operation 为空）。 */
	@GetMapping("/unannotated")
	public Mono<Map<String, Object>> unannotated() {
		return Mono.just(body("unannotated"));
	}

	private static Map<String, Object> body(String kind) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("kind", kind);
		map.put("thread", Thread.currentThread().getName());
		return map;
	}
}
