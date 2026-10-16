package io.github.pkyit.auditlog.samples;

import io.github.pkyit.auditlog.core.model.ActionType;
import io.github.pkyit.auditlog.spring.annotation.AuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 示例控制器：覆盖审计的关键场景。
 *
 * <p>端点分为三组：查询类端点（{@code /user/queryUserInfo}、{@code /user/queryUserInfo2}、
 * {@code /user/queryUserInfo3}、{@code /user/queryUserInfoNoAudit}），
 * 脱敏与载荷采集端点，以及事务边界与错误归因端点。
 */
@RestController
@RequestMapping("/user")
public class UserController {

	private final DemoUserService userService;

	/**
	 * 注入业务服务。
	 *
	 * @param userService 提供事务提交与回滚场景的业务服务
	 */
	public UserController(DemoUserService userService) {
		this.userService = userService;
	}

	// ------------------------------------------------------------------
	// 查询端点
	// ------------------------------------------------------------------

	/**
	 * 查询用户信息。
	 *
	 * <p>{@code actionType} 与 {@code resourceId} 补充审计语义，使记录可按动作类型聚合、
	 * 按资源定位；缺少这两个字段时，记录只能按操作名检索。
	 */
	@AuditLog(name = "查询用户信息", actionType = ActionType.QUERY, resourceType = "user",
		resourceIdParam = "userId")
	@GetMapping("/queryUserInfo")
	public String getUserInfoByUserId(@RequestParam String userId) {
		return userId;
	}

	/** 查询用户信息：响应为 {@code userId} 与 {@code deptId} 的拼接，用于多参数方法的审计采集。 */
	@AuditLog(name = "查询用户信息2", actionType = ActionType.QUERY, resourceType = "user",
		resourceIdParam = "userId")
	@GetMapping("/queryUserInfo2")
	public String getUserInfoByUserId2(@RequestParam String userId, @RequestParam String deptId) {
		return userId + deptId;
	}

	/**
	 * 查询用户信息（三项采集全关）。
	 *
	 * <p>开关保持 false 以保留对照能力：该配置下的吞吐不含载荷采集成本，
	 * 不能代表真实开销。四组对照压测设计见 {@code docs/04-性能验证方案.md}。
	 */
	@AuditLog(name = "查询用户信息3", actionType = ActionType.QUERY, logHeaders = false,
		logRequestParams = false, logResponseParams = false)
	@GetMapping("/queryUserInfo3")
	public List<String> getUserInfoByUserId3(@RequestParam String userName) {
		List<String> objects = new ArrayList<>();
		objects.add("张三");
		objects.add("李斯");
		objects.add(userName);
		objects.add("wangwu");
		return objects;
	}

	/** 未标注注解：边界通道兜底采集，仍会产生记录，但记录中不含语义字段。 */
	@GetMapping("/queryUserInfoNoAudit")
	public List<String> queryUserInfoNoAudit(@RequestParam String userName) {
		List<String> objects = new ArrayList<>();
		objects.add(userName);
		return objects;
	}

	// ------------------------------------------------------------------
	// 脱敏与载荷采集
	// ------------------------------------------------------------------

	/**
	 * 含敏感字段的写入端点，用于验证脱敏。
	 *
	 * <p>契约：请求体中的 {@code password} / {@code idCard} / {@code mobile} 必须被掩码；
	 * 请求头 {@code Authorization} 也必须被掩码，且请求头名匹配不区分大小写。
	 */
	@AuditLog(name = "创建用户", actionType = ActionType.MUTATE, resourceType = "user",
		logHeaders = true, logRequestParams = true, sensitive = true)
	@PostMapping("/create")
	public Map<String, Object> createUser(@RequestBody Map<String, Object> body) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("code", 0);
		result.put("message", "created");
		result.put("id", 1001);
		return result;
	}

	/** 需要已认证主体的端点：未登录时返回 401；认证失败必须同样被审计。 */
	@AuditLog(name = "查询我的信息", actionType = ActionType.SENSITIVE_ACCESS, logRequestParams = false)
	@GetMapping("/me")
	public Map<String, Object> me() {
		throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
	}

	/** 显式拒绝：403 必须被记录为 DENIED。 */
	@AuditLog(name = "删除用户", actionType = ActionType.MUTATE, resourceType = "user")
	@DeleteMapping("/{id}")
	public Map<String, Object> deleteUser(@PathVariable Long id) {
		throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限删除");
	}

	// ------------------------------------------------------------------
	// 事务边界与错误归因
	// ------------------------------------------------------------------

	/** 事务提交：必须产生 SUCCESS 记录。 */
	@AuditLog(name = "写入并提交", actionType = ActionType.MUTATE, resourceType = "demo")
	@PostMapping("/tx/commit")
	public Map<String, Object> insertThenCommit(@RequestParam String name) {
		String saved = userService.insertThenCommit(name);
		return Map.of("saved", saved, "count", userService.countRecords());
	}

	/** 事务回滚：默认策略下不得产生「成功」记录，否则审计结论与数据库状态相反。 */
	@AuditLog(name = "写入并回滚", actionType = ActionType.MUTATE, resourceType = "demo")
	@PostMapping("/tx/rollback")
	public Map<String, Object> insertThenRollback(@RequestParam String name) {
		userService.insertThenRollback(name);
		return Map.of("unreachable", true);
	}

	/** 触发未捕获异常：必须记录为 FAILURE，并保留异常类型与根因。 */
	@AuditLog(name = "触发异常", actionType = ActionType.UNKNOWN)
	@GetMapping("/boom")
	public String boom() {
		throw new IllegalStateException("示例异常：用于验证审计的错误归因");
	}

	/** 示例表当前记录数，用于断言回滚后没有落库。 */
	@GetMapping("/tx/count")
	public Map<String, Object> count() {
		return Map.of("count", userService.countRecords());
	}
}
