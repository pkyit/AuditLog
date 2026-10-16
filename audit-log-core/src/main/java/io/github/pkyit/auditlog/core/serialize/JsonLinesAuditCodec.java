package io.github.pkyit.auditlog.core.serialize;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.pkyit.auditlog.core.api.AuditCodec;
import io.github.pkyit.auditlog.core.model.ActionType;
import io.github.pkyit.auditlog.core.model.AuditRecord;
import io.github.pkyit.auditlog.core.model.BusinessResult;
import io.github.pkyit.auditlog.core.model.ErrorInfo;
import io.github.pkyit.auditlog.core.model.Outcome;
import io.github.pkyit.auditlog.core.model.Payload;
import io.github.pkyit.auditlog.core.model.PrincipalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 默认编解码实现：单行 JSON（JSON Lines）。
 *
 * <p>线格式契约为一条记录一行：追加写友好，进程崩溃最多损坏最后一行；
 * 输出可被 Flume/Filebeat/Vector 等采集器直接消费，无需自研解析器，
 * 也可用 {@code grep}/{@code jq} 直接检索。
 *
 * <p>不使用宿主应用的 {@code ObjectMapper}：审计线格式必须跨服务、跨版本稳定，
 * 不能随宿主的命名策略或日期格式配置漂移。
 *
 * <p>本类无可变状态，持有的 {@link ObjectMapper} 在配置完成后即可安全并发读写，
 * 实例可被多线程共享。
 */
public class JsonLinesAuditCodec implements AuditCodec {

	private static final Logger log = LoggerFactory.getLogger(JsonLinesAuditCodec.class);

	private final ObjectMapper mapper;

	/**
	 * 使用 {@link #defaultMapper()} 创建的映射器。
	 */
	public JsonLinesAuditCodec() {
		this(defaultMapper());
	}

	/**
	 * 使用指定的映射器。
	 *
	 * @param mapper 负责实际读写 JSON 的映射器；其序列化配置必须与 {@link #defaultMapper()} 一致，
	 *               否则同一记录在不同实例下的编码结果不同
	 */
	public JsonLinesAuditCodec(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * 构造编解码默认使用的映射器。
	 *
	 * <p>禁用 {@code FAIL_ON_UNKNOWN_PROPERTIES}，使新增字段的记录在旧版本消费方仍可解码；
	 * 禁用 {@code WRITE_DATES_AS_TIMESTAMPS}，{@code java.time} 值以 ISO-8601 文本输出，
	 * 避免消费方对时间戳单位的歧义；禁用 {@code FAIL_ON_EMPTY_BEANS}，
	 * 无属性对象输出为空 JSON 对象而不是编码失败。
	 *
	 * @return 新映射器；由调用方持有，本类不做后续配置
	 */
	public static ObjectMapper defaultMapper() {
		return JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build()
			.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
			.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
				com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS);
	}

	/**
	 * 将审计记录编码为单行 JSON。
	 *
	 * <p>字段以固定顺序写入，取值为 {@code null} 的可选字段输出为 JSON {@code null}；
	 * 仅当标签为空时不输出 {@code tags}，仅当错误为空时不输出 {@code error}。
	 * 同一记录在同配置下编码结果稳定，可用于对账与逐行 diff。
	 *
	 * <p>本方法不抛异常：序列化失败时返回仅含 {@code id}、{@code seq}、
	 * {@code outcome=UNKNOWN} 与 {@code encodingError} 的最小 JSON，记录不会静默丢失。
	 *
	 * @param record 待编码记录
	 * @return 不含换行符的单行 JSON
	 */
	@Override
	public String encode(AuditRecord record) {
		Map<String, Object> map = new LinkedHashMap<>(40);
		map.put("schemaVersion", record.getSchemaVersion());
		map.put("id", record.getId());
		map.put("seq", record.getSeq());
		map.put("eventTime", record.getEventTime() == null ? null : record.getEventTime().toString());
		map.put("receivedTime", record.getReceivedTime() == null ? null : record.getReceivedTime().toString());
		map.put("zoneId", record.getZoneId());
		map.put("principalId", record.getPrincipalId());
		map.put("principalName", record.getPrincipalName());
		map.put("principalType", record.getPrincipalType() == null ? null : record.getPrincipalType().name());
		map.put("tenantId", record.getTenantId());
		map.put("sessionId", record.getSessionId());
		map.put("authType", record.getAuthType());
		map.put("traceId", record.getTraceId());
		map.put("operation", record.getOperation());
		map.put("actionType", record.getActionType() == null ? null : record.getActionType().name());
		map.put("resourceType", record.getResourceType());
		map.put("resourceId", record.getResourceId());
		if (!record.getTags().isEmpty()) {
			map.put("tags", record.getTags());
		}
		map.put("clientIp", record.getClientIp());
		map.put("userAgent", record.getUserAgent());
		map.put("httpMethod", record.getHttpMethod());
		map.put("requestUri", record.getRequestUri());
		map.put("queryString", record.getQueryString());
		map.put("serviceName", record.getServiceName());
		map.put("instanceId", record.getInstanceId());
		map.put("request", payloadMap(record.getRequestPayload()));
		map.put("response", payloadMap(record.getResponsePayload()));
		map.put("outcome", record.getOutcome() == null ? null : record.getOutcome().name());
		map.put("httpStatus", record.getHttpStatus());
		map.put("bizCode", record.getBusinessResult() == null ? null : record.getBusinessResult().code());
		map.put("bizMessage", record.getBusinessResult() == null ? null : record.getBusinessResult().message());
		map.put("bizSuccess", record.getBusinessResult() != null && record.getBusinessResult().success());
		if (record.getError() != null) {
			Map<String, Object> errorMap = new LinkedHashMap<>(5);
			errorMap.put("type", record.getError().type());
			errorMap.put("message", record.getError().message());
			errorMap.put("rootCauseType", record.getError().rootCauseType());
			errorMap.put("rootCauseMessage", record.getError().rootCauseMessage());
			if (record.getError().stackTrace() != null) {
				errorMap.put("stackTrace", record.getError().stackTrace());
			}
			map.put("error", errorMap);
		}
		map.put("durationMillis", record.getDurationMillis());
		map.put("transactional", record.isTransactional());
		map.put("rolledBack", record.isRolledBack());
		map.put("prevHash", record.getPrevHash());
		map.put("recordHash", record.getRecordHash());
		map.put("signature", record.getSignature());
		try {
			return mapper.writeValueAsString(map);
		}
		catch (Exception error) {
			log.error("[audit-log] 审计记录编码失败 id={}", record.getId(), error);
			// 编码失败也必须留下痕迹，否则就是静默丢数据
			return "{\"id\":\"" + record.getId() + "\",\"seq\":" + record.getSeq()
				+ ",\"outcome\":\"UNKNOWN\",\"encodingError\":\"" + error.getClass().getName() + "\"}";
		}
	}

	/**
	 * 将单行 JSON 解码为审计记录。
	 *
	 * <p>输入必须是单行 JSON 对象：多行内容、以及崩溃时被截断的最后一行，都会解析失败。
	 * 任何解析失败都返回 {@code null} 而不抛异常，并记录 warn 日志，
	 * 由调用方决定丢弃还是投递死信。
	 *
	 * <p>字段缺失或取值非法时按退化值回填：枚举回退为 {@link PrincipalType#ANONYMOUS}、
	 * {@link ActionType#UNKNOWN}、{@link Outcome#UNKNOWN}，{@code seq} 与 {@code durationMillis}
	 * 回退为 {@code -1}；退化值与业务真实取值无法区分，需要区分时必须回看原始行。
	 * 数字字段出现非数字文本（如 {@code httpStatus} 写成字符串）会使整行解码失败。
	 *
	 * @param line 单行 JSON，允许为 {@code null}
	 * @return 解码结果；{@code line} 为 {@code null}、空白或无法解析时为 {@code null}
	 */
	@Override
	@SuppressWarnings("unchecked")
	public AuditRecord decode(String line) {
		if (line == null || line.isBlank()) {
			return null;
		}
		try {
			Map<String, Object> map = mapper.readValue(line, Map.class);
			AuditRecord.Builder builder = new AuditRecord.Builder();
			builder.id(string(map.get("id")));
			builder.seq(longValue(map.get("seq"), -1L));
			builder.eventTime(instant(map.get("eventTime")));
			builder.receivedTime(instant(map.get("receivedTime")));
			builder.zoneId(string(map.get("zoneId")));
			builder.principal(string(map.get("principalId")), string(map.get("principalName")),
				enumValue(PrincipalType.class, map.get("principalType"), PrincipalType.ANONYMOUS));
			builder.tenantId(string(map.get("tenantId")));
			builder.sessionId(string(map.get("sessionId")));
			builder.authType(string(map.get("authType")));
			builder.traceId(string(map.get("traceId")));
			builder.operation(string(map.get("operation")));
			builder.actionType(enumValue(ActionType.class, map.get("actionType"), ActionType.UNKNOWN));
			builder.resource(string(map.get("resourceType")), string(map.get("resourceId")));
			Object tags = map.get("tags");
			if (tags instanceof Map<?, ?> tagMap) {
				tagMap.forEach((key, value) -> builder.tag(String.valueOf(key), string(value)));
			}
			builder.clientIp(string(map.get("clientIp")));
			builder.userAgent(string(map.get("userAgent")));
			builder.http(string(map.get("httpMethod")), string(map.get("requestUri")), string(map.get("queryString")));
			builder.service(string(map.get("serviceName")), string(map.get("instanceId")));
			builder.requestPayload(payload(map.get("request")));
			builder.responsePayload(payload(map.get("response")));
			builder.outcome(enumValue(Outcome.class, map.get("outcome"), Outcome.UNKNOWN));
			builder.httpStatus(integer(map.get("httpStatus")));
			builder.businessResult(new BusinessResult(integer(map.get("bizCode")), string(map.get("bizMessage")),
				Boolean.TRUE.equals(map.get("bizSuccess"))));
			builder.error(error(map.get("error")));
			builder.durationMillis(longValue(map.get("durationMillis"), -1L));
			builder.transactional(Boolean.TRUE.equals(map.get("transactional")));
			builder.rolledBack(Boolean.TRUE.equals(map.get("rolledBack")));
			AuditRecord record = builder.build();
			// 链字段通过 withChain 回填，保证不可变语义
			return record.withChain(string(map.get("prevHash")), string(map.get("recordHash")),
				string(map.get("signature")));
		}
		catch (Exception error) {
			log.warn("[audit-log] 审计记录解码失败: {}", error.toString());
			return null;
		}
	}

	private static Map<String, Object> payloadMap(Payload payload) {
		Map<String, Object> map = new LinkedHashMap<>(4);
		if (payload == null) {
			return map;
		}
		map.put("contentType", payload.contentType());
		map.put("body", payload.body());
		map.put("originalSize", payload.originalSize());
		map.put("truncated", payload.truncated());
		return map;
	}

	// 取值不是对象时（含字段整体缺失）回退为 Payload.EMPTY；
	// 编码端为 null 载荷写出的空对象 {} 会解码为字段全空的 Payload 而非 EMPTY
	private static Payload payload(Object value) {
		if (!(value instanceof Map<?, ?> map)) {
			return Payload.EMPTY;
		}
		return new Payload(string(map.get("contentType")), string(map.get("body")),
			longValue(map.get("originalSize"), 0L), Boolean.TRUE.equals(map.get("truncated")));
	}

	@SuppressWarnings("unchecked")
	private static ErrorInfo error(Object value) {
		if (!(value instanceof Map<?, ?> map)) {
			return null;
		}
		return new ErrorInfo(string(((Map<String, Object>) map).get("type")),
			string(((Map<String, Object>) map).get("message")),
			string(((Map<String, Object>) map).get("rootCauseType")),
			string(((Map<String, Object>) map).get("rootCauseMessage")),
			string(((Map<String, Object>) map).get("stackTrace")));
	}

	private static String string(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	// 非数字文本会抛出 NumberFormatException，由调用方（decode）的 catch 收敛为整行解码失败
	private static Integer integer(Object value) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		return value == null ? null : Integer.valueOf(String.valueOf(value));
	}

	// 无法解析时返回 defaultValue 而不抛异常，调用方以 -1 表示字段缺失
	private static long longValue(Object value, long defaultValue) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value == null) {
			return defaultValue;
		}
		try {
			return Long.parseLong(String.valueOf(value));
		}
		catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}

	// 与编码端的 Instant.toString() 对称，只接受 ISO-8601 文本；解析失败返回 null 而不抛异常
	private static Instant instant(Object value) {
		if (value == null) {
			return null;
		}
		try {
			return Instant.parse(String.valueOf(value));
		}
		catch (Exception ignored) {
			return null;
		}
	}

	// 未知枚举名回退为 defaultValue，使新增枚举常量的记录在旧版本消费方仍可解码
	private static <E extends Enum<E>> E enumValue(Class<E> type, Object value, E defaultValue) {
		if (value == null) {
			return defaultValue;
		}
		try {
			return Enum.valueOf(type, String.valueOf(value));
		}
		catch (IllegalArgumentException ignored) {
			return defaultValue;
		}
	}
}
