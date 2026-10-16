package io.github.pkyit.auditlog.samples;

import io.github.pkyit.auditlog.core.model.ActionType;
import io.github.pkyit.auditlog.spring.annotation.AuditLog;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 性能对照端点。
 *
 * <p>压测结论只在对照组之间唯一变量为审计配置时成立。因此这里提供同一份业务逻辑、
 * 不同审计配置的端点，使测得的差异可归因于审计开销。
 *
 * <p>四组对照：
 * <ul>
 *   <li>{@code /load/plain} —— 基线：不生成审计记录（审计框架开启但该请求不被采集）；</li>
 *   <li>{@code /load/off} —— 标注注解但三项采集全关：测量注解与方法级记录的固定开销；</li>
 *   <li>{@code /load/full} —— 全开：请求头 + 请求体 + 脱敏，最接近生产环境的真实成本；</li>
 *   <li>{@code /load/inline} + {@code audit.log.enabled=false} —— 完全无审计（用独立 profile 跑）。</li>
 * </ul>
 *
 * <p>业务逻辑保持极简（只做字符串拼接），确保测到的是审计开销而不是业务开销。
 */
@RestController
@RequestMapping("/load")
public class LoadTestController {

	/**
	 * 基线端点：无审计注解。
	 *
	 * <p>审计框架默认仍会为未标注的请求产生边界记录，因此该端点衡量的是边界采集成本，
	 * 而非零成本。
	 */
	@GetMapping("/plain")
	public Map<String, Object> plain(@RequestParam(defaultValue = "x") String payload) {
		return response(payload, "plain");
	}

	/** 标注注解但三项采集全关：用于隔离注解与方法级记录的固定开销。 */
	@AuditLog(name = "压测-全关", actionType = ActionType.QUERY,
		logHeaders = false, logRequestParams = false, logResponseParams = false)
	@GetMapping("/off")
	public Map<String, Object> auditOff(@RequestParam(defaultValue = "x") String payload) {
		return response(payload, "audit-off");
	}

	/**
	 * 全开：请求头 + 请求体采集并脱敏，最接近生产环境的真实成本。
	 */
	@AuditLog(name = "压测-全开", actionType = ActionType.MUTATE, resourceType = "load",
		logHeaders = true, logRequestParams = true, logResponseParams = false)
	@GetMapping("/full")
	public Map<String, Object> auditFull(@RequestParam(defaultValue = "x") String payload) {
		return response(payload, "audit-full");
	}

	/**
	 * 全开 + 较大的 JSON 请求体：用于测量序列化与脱敏随报文增长的成本，
	 * 并验证 {@code max-body-length} 截断是否生效。
	 */
	@AuditLog(name = "压测-大报文", actionType = ActionType.IMPORT, resourceType = "load",
		logHeaders = true, logRequestParams = true)
	@PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> auditWithBody(@RequestBody Map<String, Object> body) {
		Map<String, Object> result = response(String.valueOf(body.size()), "audit-body");
		result.put("receivedKeys", body.size());
		return result;
	}

	/** 深度嵌套载荷：验证脱敏的递归深度限制不会导致栈溢出或超长耗时。 */
	@AuditLog(name = "压测-深嵌套", actionType = ActionType.IMPORT, logRequestParams = true)
	@PostMapping(value = "/nested", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> auditNested(@RequestBody Map<String, Object> body) {
		return response("nested", "audit-nested");
	}

	private Map<String, Object> response(String payload, String kind) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("kind", kind);
		result.put("echoLength", payload == null ? 0 : payload.length());
		result.put("ok", true);
		return result;
	}

	/**
	 * 回显业务侧收到的请求体长度与 SHA-256 摘要。
	 *
	 * <p>请求体包装不得改变业务读到的数据：业务侧必须收到完整报文。
	 * 若 {@code receivedLength} 恒等于审计侧缓存上限
	 * （{@code audit.log.collector.boundary.max-cached-body-bytes}，默认 64KB），
	 * 说明缓存上限被透传给了业务侧，业务输入被截断。
	 */
	@org.springframework.web.bind.annotation.PostMapping("/echo")
	public java.util.Map<String, Object> echo(
			@org.springframework.web.bind.annotation.RequestBody(required = false) byte[] body)
			throws java.security.NoSuchAlgorithmException {
		byte[] actual = body == null ? new byte[0] : body;
		String sha = java.util.HexFormat.of().formatHex(
			java.security.MessageDigest.getInstance("SHA-256").digest(actual));
		java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
		result.put("receivedLength", actual.length);
		result.put("sha256", sha);
		return result;
	}
}
