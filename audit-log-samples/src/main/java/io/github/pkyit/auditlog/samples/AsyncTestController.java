package io.github.pkyit.auditlog.samples;

import io.github.pkyit.auditlog.spring.annotation.AuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Servlet 异步模型下的审计采集验证端点。
 *
 * <p>{@code Callable}/{@code DeferredResult}/{@code WebAsyncTask}/SSE 场景下，
 * {@code startAsync()} 之后初始线程立即返回。审计过滤器若在此时收尾，只会记录占位的
 * 200 状态码与截至 {@code startAsync} 的耗时；ASYNC dispatch 二次进入又会被
 * {@code OncePerRequestFilter} 默认跳过，记录无法被修正，最终形成"假成功 + 假耗时"
 * 的系统性错误。覆盖这些返回类型的端点用于暴露该类错误。
 *
 * <p>耗时类断言要求耗时显著大于 0，因此主要场景均提供延迟 {@value #SLOW_MILLIS} 毫秒的慢版本。
 */
@RestController
@RequestMapping("/async")
public class AsyncTestController {

	private static final long SLOW_MILLIS = 800L;

	/** Callable：最常见的异步控制器返回值。 */
	@GetMapping("/callable")
	public Callable<Map<String, Object>> callable(@RequestParam(defaultValue = "0") long delayMillis) {
		return () -> {
			sleep(delayMillis);
			return body("callable", delayMillis, 200);
		};
	}

	/** 带 @AuditLog 的 Callable：验证方法级通道与边界通道在异步下不重复发布。 */
	@AuditLog("异步-Callable-已标注")
	@GetMapping("/callable-annotated")
	public Callable<Map<String, Object>> callableAnnotated(
			@RequestParam(defaultValue = "0") long delayMillis) {
		return () -> {
			sleep(delayMillis);
			return body("callable-annotated", delayMillis, 200);
		};
	}

	/** Callable 抛异常：验证异步异常的 outcome 与 error 字段。 */
	@GetMapping("/callable-error")
	public Callable<Map<String, Object>> callableError() {
		return () -> {
			sleep(120L);
			throw new IllegalStateException("异步任务内部失败");
		};
	}

	/** Callable 返回 500：状态码必须被记录为 500，而不是占位 200。 */
	@GetMapping("/callable-500")
	public Callable<ResponseEntity<Map<String, Object>>> callable500() {
		return () -> {
			sleep(120L);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(body("callable-500", 120L, 500));
		};
	}

	/** Callable 返回 404：非 2xx 状态码在异步下同样必须被记录。 */
	@GetMapping("/callable-404")
	public Callable<ResponseEntity<Map<String, Object>>> callable404() {
		return () -> {
			sleep(120L);
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(body("callable-404", 120L, 404));
		};
	}

	/**
	 * DeferredResult：由另一个线程稍后设置结果。
	 *
	 * <p>{@code httpStatus} 会真实影响 HTTP 响应码：取值大于等于 400 时通过
	 * {@code setErrorResult} 让容器按该状态码收尾，而不是只改响应体里的一个字段。
	 * 状态码必须由容器真实提交，否则审计记录到的响应码无法被校验。
	 *
	 * @param delayMillis 设置结果前的延迟毫秒数
	 * @param httpStatus 期望的 HTTP 响应码；大于等于 400 时按错误结果收尾
	 * @return 结果由 {@code CompletableFuture} 在公共线程池设置；5000ms 内未设置则按超时收尾
	 */
	@GetMapping("/deferred")
	public DeferredResult<Object> deferred(
			@RequestParam(defaultValue = "0") long delayMillis,
			@RequestParam(defaultValue = "200") int httpStatus) {
		DeferredResult<Object> result = new DeferredResult<>(5_000L);
		CompletableFuture.runAsync(() -> {
			sleep(delayMillis);
			if (httpStatus >= 400) {
				// 用真实的状态码收尾，让审计侧必须记录这个码而不是占位 200
				result.setErrorResult(ResponseEntity.status(httpStatus)
					.body(body("deferred", delayMillis, httpStatus)));
			}
			else {
				result.setResult(body("deferred", delayMillis, httpStatus));
			}
		});
		return result;
	}

	/** DeferredResult 超时：验证超时场景下审计记录不丢失。 */
	@GetMapping("/deferred-timeout")
	public DeferredResult<Map<String, Object>> deferredTimeout() {
		DeferredResult<Map<String, Object>> result = new DeferredResult<>(300L);
		// 不设置结果，由 300ms 超时收尾
		return result;
	}

	/** StreamingResponseBody：边写边发，验证流式响应的收尾时机。 */
	@GetMapping("/streaming")
	public StreamingResponseBody streaming(@RequestParam(defaultValue = "3") int chunks) {
		return (OutputStream output) -> {
			for (int index = 0; index < chunks; index++) {
				output.write(("chunk-" + index + "\n").getBytes(StandardCharsets.UTF_8));
				output.flush();
				sleep(60L);
			}
		};
	}

	/** CompletableFuture：Spring MVC 同样按异步处理。 */
	@GetMapping("/future")
	public CompletableFuture<Map<String, Object>> future(
			@RequestParam(defaultValue = "0") long delayMillis) {
		return CompletableFuture.supplyAsync(() -> {
			sleep(delayMillis);
			return body("future", delayMillis, 200);
		});
	}

	/**
	 * POST 异步 + 请求体（边界通道）。
	 *
	 * <p>边界通道只记录元信息（方法/URI/状态码/耗时），不采集请求体：载荷采集属于语义通道
	 * （{@code @AuditLog}）的职责。该端点覆盖异步下的边界记录，请求体采集与脱敏应以
	 * {@code /async/post-annotated} 为验证对象。
	 */
	@PostMapping("/post-callable")
	public Callable<Map<String, Object>> postCallable(
			@org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, Object> payload) {
		return () -> {
			sleep(150L);
			Map<String, Object> result = body("post-callable", 150L, 200);
			result.put("receivedKeys", payload == null ? java.util.List.of() : payload.keySet());
			return result;
		};
	}

	/**
	 * POST 异步 + 请求体（语义通道，显式开启参数采集）。
	 *
	 * <p>带 {@code @AuditLog(logRequestParams = true)}，载荷采集被启用，
	 * 因此异步下请求体是否被正确采集与脱敏以该端点为验证对象。
	 */
	@AuditLog(value = "异步-POST-已标注", logRequestParams = true)
	@PostMapping("/post-annotated")
	public Callable<Map<String, Object>> postAnnotated(
			@org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, Object> payload) {
		return () -> {
			sleep(150L);
			Map<String, Object> result = body("post-annotated", 150L, 200);
			result.put("receivedKeys", payload == null ? java.util.List.of() : payload.keySet());
			return result;
		};
	}

	/** 慢速 Callable：固定延迟 {@value #SLOW_MILLIS} 毫秒，便于判定耗时是否计到异步完成。 */
	@GetMapping("/slow")
	public Callable<Map<String, Object>> slow() {
		return callable(SLOW_MILLIS);
	}

	private static Map<String, Object> body(String kind, long delayMillis, int status) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("kind", kind);
		map.put("delayMillis", delayMillis);
		map.put("status", status);
		map.put("thread", Thread.currentThread().getName());
		return map;
	}

	private static void sleep(long millis) {
		if (millis <= 0) {
			return;
		}
		try {
			TimeUnit.MILLISECONDS.sleep(millis);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
