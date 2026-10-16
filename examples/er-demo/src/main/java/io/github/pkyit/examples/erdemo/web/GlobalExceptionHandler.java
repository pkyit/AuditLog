package io.github.pkyit.examples.erdemo.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常处理。
 *
 * <p>作用之一是让"业务失败"以明确的状态码返回，而不是一律 500 ——
 * 审计记录里的 {@code httpStatus} 与 {@code outcome} 应当反映真实结果，
 * 若所有失败都变成 500，就无法区分"客户端参数错误"与"服务端故障"。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** 参数非法：客户端的错，返回 400。 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException error) {
		return build(HttpStatus.BAD_REQUEST, error.getMessage());
	}

	/** 业务规则不满足（如库存不足）：语义上是冲突，返回 409。 */
	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, Object>> conflict(IllegalStateException error) {
		return build(HttpStatus.CONFLICT, error.getMessage());
	}

	/** 外键约束等完整性问题：返回 409 并记录底层原因，便于定位。 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException error) {
		log.warn("数据完整性约束被触发", error);
		return build(HttpStatus.CONFLICT, "数据完整性约束被触发");
	}

	private static ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", status.value());
		body.put("message", message);
		return ResponseEntity.status(status).body(body);
	}
}
