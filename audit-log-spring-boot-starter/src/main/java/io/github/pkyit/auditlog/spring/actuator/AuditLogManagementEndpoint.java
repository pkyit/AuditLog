package io.github.pkyit.auditlog.spring.actuator;

import io.github.pkyit.auditlog.core.model.AuditRecord;
import io.github.pkyit.auditlog.core.pipeline.AuditPipeline;
import io.github.pkyit.auditlog.spring.method.AuditMethodMetadata;
import io.github.pkyit.auditlog.spring.method.AuditMethodRegistry;
import io.github.pkyit.auditlog.spring.properties.AuditLogProperties;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计管理端点：{@code GET /actuator/auditLog}。
 *
 * <p>输出注解收录清单、采集配置与流水线状态，用于核对哪些方法真的在采集：
 * 注解未命中时既不产生记录也不影响业务返回，只能由该端点直接读出收录结果。
 */
@Endpoint(id = "auditLog")
public class AuditLogManagementEndpoint {

	private final AuditMethodRegistry registry;
	private final AuditPipeline pipeline;
	private final AuditLogProperties properties;

	/**
	 * 创建管理端点。
	 *
	 * @param registry   注解收录注册表；为 {@code null} 时收录相关字段返回 {@code -1} 或空列表
	 * @param pipeline   审计流水线；为 {@code null} 时流水线字段返回 {@code -1}，链校验字段不输出
	 * @param properties 配置；不可为 {@code null}，否则 {@link #auditLog()} 抛 {@code NullPointerException}
	 */
	public AuditLogManagementEndpoint(AuditMethodRegistry registry, AuditPipeline pipeline,
			AuditLogProperties properties) {
		this.registry = registry;
		this.pipeline = pipeline;
		this.properties = properties;
	}

	/**
	 * 汇总采集配置、注解收录清单与流水线状态。
	 *
	 * <p>组件缺失时相关字段使用占位值（{@code -1}、{@code "none"} 或 {@code null}）；
	 * 收录清单 {@code collected} 与链校验 {@code chainVerification} 仅在注册表、
	 * 流水线存在时出现。
	 *
	 * @return 键值对映射，永不为 {@code null}
	 */
	@ReadOperation
	public Map<String, Object> auditLog() {		Map<String, Object> result = new LinkedHashMap<>();
		result.put("enabled", properties.isEnabled());
		result.put("serviceName", properties.getServiceName());
		// 诊断字段允许为 null（哈希链未启用时的链头、未命中注解时的操作名），
		// 因此只能使用 LinkedHashMap.put：Map.of() 拒绝 null 值并抛 NullPointerException，
		// 会把「字段暂时没有值」这一正常状态变成端点 500。
		result.put("collector", linkedMap(
			"boundaryEnabled", properties.getCollector().getBoundary().isEnabled(),
			"methodEnabled", properties.getCollector().getMethod().isEnabled(),
			"serializeArguments", properties.getCollector().getMethod().isSerializeArguments()));
		result.put("store", linkedMap(
			"type", properties.getStore().getType(),
			"name", pipeline == null || pipeline.store() == null ? "none" : pipeline.store().getName()));
		result.put("chain", linkedMap(
			"enabled", pipeline != null && pipeline.chain() != null && pipeline.chain().isEnabled(),
			"head", pipeline == null || pipeline.chain() == null ? null : pipeline.chain().currentHead()));
		result.put("pipeline", linkedMap(
			"queueDepth", pipeline == null ? -1 : pipeline.queueDepth(),
			"queueCapacity", properties.getPipeline().getQueueCapacity(),
			"strategy", String.valueOf(properties.getPipeline().getStrategy()),
			"droppedRecords", pipeline == null ? -1L : pipeline.droppedCount(),
			"lastDeliveredSeq", pipeline == null ? -1L : pipeline.lastDeliveredSeq()));
		result.put("validation", linkedMap(
			"scannedHandlers", registry == null ? -1 : registry.scannedHandlerCount(),
			"collectedMethods", registry == null ? -1 : registry.collectedMethodCount(),
			"excludedHandlers", registry == null ? List.of() : registry.excludedHandlers()));

		if (registry != null) {
			Map<String, Object> collected = new LinkedHashMap<>();
			for (Map.Entry<String, AuditMethodMetadata> entry : registry.all().entrySet()) {
				AuditMethodMetadata metadata = entry.getValue();
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("operation", metadata.operation());
				item.put("actionType", String.valueOf(metadata.actionType()));
				item.put("resourceType", metadata.resourceType());
				if (metadata.sensitive()) {
					item.put("sensitive", true);
				}
				collected.put(entry.getKey(), item);
			}
			result.put("collected", collected);
		}

		// 链校验只覆盖内存中最近投递的记录（受 pipeline.recent-cache-size 限制），
		// 因此同时输出已校验条数与最近记录条数，便于判断校验范围。
		if (pipeline != null) {
			var verification = pipeline.verifyChain();
			result.put("chainVerification", linkedMap(
				"enabled", pipeline.chain() != null && pipeline.chain().isEnabled(),
				"valid", verification.valid(),
				"verifiedCount", verification.verifiedCount(),
				"brokenAtSeq", verification.brokenAtSeq(),
				"reason", verification.reason()));
			List<AuditRecord> recent = pipeline.recentRecords();
			result.put("recentRecordCount", recent.size());
		}
		return result;
	}

	/**
	 * 构造允许 null 值、且保留插入顺序的键值对映射。
	 *
	 * <p>不能使用 {@code Map.of(...)}：该方法对 null 值抛 {@code NullPointerException}，
	 * 而诊断端点中「字段暂时没有值」是正常状态（哈希链未启用时的链头即为 null），
	 * 抛出异常会把正常状态变成失败响应。键统一转为字符串，值原样放入。
	 *
	 * @param keyValues 键值交替排列的参数；末尾落单的键被忽略
	 * @return 保留插入顺序的可变映射
	 */
	private static Map<String, Object> linkedMap(Object... keyValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int index = 0; index + 1 < keyValues.length; index += 2) {
			map.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
		}
		return map;
	}
}
