package io.github.pkyit.auditlog.core.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 不可变审计记录 —— 框架的核心数据模型。
 *
 * <p>设计要点：
 * <ul>
 *   <li>唯一事件 ID 与单调序号，用于去重与定位；</li>
 *   <li>区分「事件发生时间」与「接收时间」，并显式携带时区；</li>
 *   <li>主体携带来源类型，不可信来源被显式标记；</li>
 *   <li>结果由 {@link Outcome} + HTTP 状态码 + 业务码共同表达；</li>
 *   <li>载荷为结构化快照而非 {@code toString()}；</li>
 *   <li>携带链路 ID 与租户，支撑微服务因果链与多租户隔离；</li>
 *   <li>携带哈希链字段与 schema 版本，支撑防篡改与格式演进。</li>
 * </ul>
 *
 * <p>本类型一经构造即不可变，可安全跨线程传递。
 */
public final class AuditRecord {

	/**
	 * 当前记录的 schema 版本。
	 *
	 * <p>摘要必须覆盖归属与来源字段：{@code clientIp}、{@code userAgent}、{@code queryString}、
	 * {@code tags}（含脱敏后的请求头）、{@code serviceName}、{@code instanceId}、
	 * {@code principalName}、{@code sessionId}、{@code authType}、{@code error.message}。
	 * 未纳入摘要的字段可被拥有存储写权限的攻击者直接改写而不破坏校验，
	 * 而「谁、从哪、查了什么」恰恰是审计取证最需要防篡改的信息：HMAC 链能挡住「重算整条链」，
	 * 但挡不住「改一个未纳入摘要的字段」。
	 *
	 * <p>摘要格式按记录自带的 {@code schemaVersion} 选择：版本 1 的记录仍用 v1 规则重算，
	 * 因此格式升级不会让历史链失效。
	 */
	public static final int SCHEMA_VERSION = 2;

	// ---- 标识 ----
	private final String id;
	private final long seq;
	private final Integer schemaVersion;

	// ---- 时间 ----
	private final Instant eventTime;
	private final Instant receivedTime;
	private final String zoneId;

	// ---- 主体 ----
	private final String principalId;
	private final String principalName;
	private final PrincipalType principalType;
	private final String tenantId;
	private final String sessionId;
	private final String authType;
	private final String traceId;

	// ---- 动作 ----
	private final String operation;
	private final ActionType actionType;
	private final String resourceType;
	private final String resourceId;
	private final Map<String, String> tags;

	// ---- 来源 ----
	private final String clientIp;
	private final String userAgent;
	private final String httpMethod;
	private final String requestUri;
	private final String queryString;
	private final String serviceName;
	private final String instanceId;

	// ---- 载荷 ----
	private final Payload requestPayload;
	private final Payload responsePayload;

	// ---- 结果 ----
	private final Outcome outcome;
	private final Integer httpStatus;
	private final BusinessResult businessResult;
	private final ErrorInfo error;
	private final long durationMillis;
	private final boolean transactional;
	private final boolean rolledBack;

	// ---- 完整性 ----
	private final String prevHash;
	private final String recordHash;
	private final String signature;

	private AuditRecord(Builder builder) {
		this.id = builder.id;
		this.seq = builder.seq;
		this.schemaVersion = SCHEMA_VERSION;
		this.eventTime = builder.eventTime;
		this.receivedTime = builder.receivedTime;
		this.zoneId = builder.zoneId;
		this.principalId = builder.principalId;
		this.principalName = builder.principalName;
		this.principalType = builder.principalType == null ? PrincipalType.ANONYMOUS : builder.principalType;
		this.tenantId = builder.tenantId;
		this.sessionId = builder.sessionId;
		this.authType = builder.authType;
		this.traceId = builder.traceId;
		this.operation = builder.operation;
		this.actionType = builder.actionType == null ? ActionType.UNKNOWN : builder.actionType;
		this.resourceType = builder.resourceType;
		this.resourceId = builder.resourceId;
		this.tags = builder.tags == null
			? Collections.emptyMap()
			: Collections.unmodifiableMap(new LinkedHashMap<>(builder.tags));
		this.clientIp = builder.clientIp;
		this.userAgent = builder.userAgent;
		this.httpMethod = builder.httpMethod;
		this.requestUri = builder.requestUri;
		this.queryString = builder.queryString;
		this.serviceName = builder.serviceName;
		this.instanceId = builder.instanceId;
		this.requestPayload = builder.requestPayload == null ? Payload.EMPTY : builder.requestPayload;
		this.responsePayload = builder.responsePayload == null ? Payload.EMPTY : builder.responsePayload;
		this.outcome = builder.outcome == null ? Outcome.UNKNOWN : builder.outcome;
		this.httpStatus = builder.httpStatus;
		this.businessResult = builder.businessResult == null ? BusinessResult.EMPTY : builder.businessResult;
		this.error = builder.error;
		this.durationMillis = builder.durationMillis;
		this.transactional = builder.transactional;
		this.rolledBack = builder.rolledBack;
		this.prevHash = builder.prevHash;
		this.recordHash = builder.recordHash;
		this.signature = builder.signature;
	}

	/**
	 * 生成用于哈希链计算的规范化内容。
	 *
	 * <p>必须排除 {@code prevHash}/{@code recordHash}/{@code signature} 自身，并保证字段顺序稳定，
	 * 使同一记录在任何 JVM 上产生相同摘要。
	 *
	 * <p>关键约束：本方法必须对解码后的记录与原始记录产生完全相同的字符串。
	 * 因此所有可能为 null 的字段都必须经 {@link #nullSafe(Object)} 规整为 {@code ""}：
	 * {@code StringBuilder.append((Object) null)} 会写入字面量 {@code "null"}，
	 * 而 {@code JsonLinesAuditCodec} 解码 null 时得到的是空串，两者不一致 ——
	 * 结果是「内存里算出的哈希」与「从审计文件重算的哈希」不相等，
	 * 审计历史的防篡改校验在离线复核时直接失效。
	 */
	public String canonicalContent() {
		// 按记录自带的版本选择格式：v1 记录仍按 v1 规则重算，
		// 因此升级到 v2 不会让历史链校验失败（这是格式演进能落地的前提）。
		Integer version = schemaVersion;
		if (version != null && version == 1) {
			return canonicalV1();
		}
		return canonicalV2();
	}

	/** v1 摘要格式（不含归属字段）。保留用于校验历史记录。 */
	private String canonicalV1() {
		return new StringBuilder(512)
			.append("v=").append(schemaVersion)
			.append("|id=").append(nullSafe(id))
			.append("|seq=").append(seq)
			.append("|eventTime=").append(nullSafe(eventTime))
			.append("|principal=").append(nullSafe(principalId))
			.append("|principalType=").append(nullSafe(principalType))
			.append("|tenant=").append(nullSafe(tenantId))
			.append("|trace=").append(nullSafe(traceId))
			.append("|operation=").append(nullSafe(operation))
			.append("|action=").append(nullSafe(actionType))
			.append("|resource=").append(nullSafe(resourceType)).append('#').append(nullSafe(resourceId))
			.append("|method=").append(nullSafe(httpMethod))
			.append("|uri=").append(nullSafe(requestUri))
			.append("|req=").append(payloadDigest(requestPayload))
			.append("|resp=").append(payloadDigest(responsePayload))
			.append("|outcome=").append(nullSafe(outcome))
			.append("|httpStatus=").append(nullSafe(httpStatus))
			.append("|bizCode=").append(nullSafe(businessResult == null ? null : businessResult.code()))
			.append("|err=").append(error == null ? "" : nullSafe(error.type()))
			.append("|duration=").append(durationMillis)
			.append("|rolledBack=").append(rolledBack)
			.toString();
	}

	/**
	 * v2 摘要格式：在 v1 字段之外，把归属与来源字段一并纳入哈希链。
	 *
	 * <p>归属字段必须参与摘要：否则拥有存储写权限的攻击者可以改写 {@code clientIp}
	 * 或 {@code queryString} 而不破坏校验，而「谁、从哪、查了什么」恰是审计取证的关键信息。
	 *
	 * <p>新增字段拼接在 v1 字段之后，保持前缀稳定，便于人工比对两种格式的差异。
	 * {@code tags} 按 key 排序后拼接，保证同一 map 在任何 JVM 上产生相同字符串。
	 */
	private String canonicalV2() {
		return new StringBuilder(640)
			.append("v=").append(schemaVersion)
			.append("|id=").append(nullSafe(id))
			.append("|seq=").append(seq)
			.append("|eventTime=").append(nullSafe(eventTime))
			.append("|principal=").append(nullSafe(principalId))
			.append("|principalType=").append(nullSafe(principalType))
			.append("|tenant=").append(nullSafe(tenantId))
			.append("|trace=").append(nullSafe(traceId))
			.append("|operation=").append(nullSafe(operation))
			.append("|action=").append(nullSafe(actionType))
			.append("|resource=").append(nullSafe(resourceType)).append('#').append(nullSafe(resourceId))
			.append("|method=").append(nullSafe(httpMethod))
			.append("|uri=").append(nullSafe(requestUri))
			.append("|req=").append(payloadDigest(requestPayload))
			.append("|resp=").append(payloadDigest(responsePayload))
			.append("|outcome=").append(nullSafe(outcome))
			.append("|httpStatus=").append(nullSafe(httpStatus))
			.append("|bizCode=").append(nullSafe(businessResult == null ? null : businessResult.code()))
			.append("|err=").append(error == null ? "" : nullSafe(error.type()))
			.append("|duration=").append(durationMillis)
			.append("|rolledBack=").append(rolledBack)
			// ---- v2 新增：归属与来源 ----
			.append("|principalName=").append(nullSafe(principalName))
			.append("|sessionId=").append(nullSafe(sessionId))
			.append("|authType=").append(nullSafe(authType))
			.append("|clientIp=").append(nullSafe(clientIp))
			.append("|userAgent=").append(nullSafe(userAgent))
			.append("|queryString=").append(nullSafe(queryString))
			.append("|serviceName=").append(nullSafe(serviceName))
			.append("|instanceId=").append(nullSafe(instanceId))
			.append("|errMsg=").append(error == null ? "" : nullSafe(error.message()))
			.append("|tags=").append(tagsDigest())
			.toString();
	}

	private static String payloadDigest(Payload payload) {
		if (payload == null || payload.isEmpty()) {
			return "";
		}
		return payload.contentType() + ':' + payload.body();
	}

	/**
	 * tags 的确定性摘要：按 key 排序后以 {@code k=v} 用 {@code &} 连接。
	 *
	 * <p>排序是必须的 —— {@code HashMap} 的迭代顺序在不同 JVM/数据下可能不同，
	 * 不排序会导致同一记录算出不同摘要，链校验随机失败。
	 */
	private String tagsDigest() {
		if (tags == null || tags.isEmpty()) {
			return "";
		}
		return tags.entrySet().stream()
			.filter(entry -> entry.getKey() != null)
			.sorted(java.util.Map.Entry.comparingByKey())
			.map(entry -> entry.getKey() + '=' + nullSafe(entry.getValue()))
			.collect(java.util.stream.Collectors.joining("&"));
	}

	/** 统一的 null 规整：null 一律表示为空串（而不是字面量 "null"）。 */
	private static String nullSafe(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	/** 返回带哈希链字段的副本（链信息在投递前统一计算，之后不再变更）。 */
	public AuditRecord withChain(String prevHash, String recordHash, String signature) {
		Builder copy = toBuilder();
		copy.prevHash = prevHash;
		copy.recordHash = recordHash;
		copy.signature = signature;
		return new AuditRecord(copy);
	}

	/** 导出为可变构建器（用于链计算或兼容层转换）。 */
	public Builder toBuilder() {
		Builder builder = new Builder();
		builder.id = this.id;
		builder.seq = this.seq;
		builder.eventTime = this.eventTime;
		builder.receivedTime = this.receivedTime;
		builder.zoneId = this.zoneId;
		builder.principalId = this.principalId;
		builder.principalName = this.principalName;
		builder.principalType = this.principalType;
		builder.tenantId = this.tenantId;
		builder.sessionId = this.sessionId;
		builder.authType = this.authType;
		builder.traceId = this.traceId;
		builder.operation = this.operation;
		builder.actionType = this.actionType;
		builder.resourceType = this.resourceType;
		builder.resourceId = this.resourceId;
		builder.tags = new LinkedHashMap<>(this.tags);
		builder.clientIp = this.clientIp;
		builder.userAgent = this.userAgent;
		builder.httpMethod = this.httpMethod;
		builder.requestUri = this.requestUri;
		builder.queryString = this.queryString;
		builder.serviceName = this.serviceName;
		builder.instanceId = this.instanceId;
		builder.requestPayload = this.requestPayload;
		builder.responsePayload = this.responsePayload;
		builder.outcome = this.outcome;
		builder.httpStatus = this.httpStatus;
		builder.businessResult = this.businessResult;
		builder.error = this.error;
		builder.durationMillis = this.durationMillis;
		builder.transactional = this.transactional;
		builder.rolledBack = this.rolledBack;
		builder.prevHash = this.prevHash;
		builder.recordHash = this.recordHash;
		builder.signature = this.signature;
		return builder;
	}

	/**
	 * 可变构建器。
	 *
	 * <p>构建期可变、构造后一次性冻结：采集需要跨阶段（边界通道建、语义通道填）逐步填充，
	 * 但对外只能暴露不可变对象，半构造状态不得泄漏到其他线程。
	 */
	public static final class Builder {

		private String id;
		private long seq = -1L;
		private Instant eventTime;
		private Instant receivedTime;
		private String zoneId;
		private String principalId;
		private String principalName;
		private PrincipalType principalType;
		private String tenantId;
		private String sessionId;
		private String authType;
		private String traceId;
		private String operation;
		private ActionType actionType;
		private String resourceType;
		private String resourceId;
		private Map<String, String> tags;
		private String clientIp;
		private String userAgent;
		private String httpMethod;
		private String requestUri;
		private String queryString;
		private String serviceName;
		private String instanceId;
		private Payload requestPayload;
		private Payload responsePayload;
		private Outcome outcome;
		private Integer httpStatus;
		private BusinessResult businessResult;
		private ErrorInfo error;
		private long durationMillis = -1L;
		private boolean transactional;
		private boolean rolledBack;
		private String prevHash;
		private String recordHash;
		private String signature;

		public Builder id(String id) {
			this.id = id;
			return this;
		}

		public Builder seq(long seq) {
			this.seq = seq;
			return this;
		}

		public Builder eventTime(Instant eventTime) {
			this.eventTime = eventTime;
			return this;
		}

		public Builder receivedTime(Instant receivedTime) {
			this.receivedTime = receivedTime;
			return this;
		}

		public Builder zoneId(String zoneId) {
			this.zoneId = zoneId;
			return this;
		}

		public Builder principal(String id, String name, PrincipalType type) {
			this.principalId = id;
			this.principalName = name;
			this.principalType = type;
			return this;
		}

		public Builder tenantId(String tenantId) {
			this.tenantId = tenantId;
			return this;
		}

		public Builder sessionId(String sessionId) {
			this.sessionId = sessionId;
			return this;
		}

		public Builder authType(String authType) {
			this.authType = authType;
			return this;
		}

		public Builder traceId(String traceId) {
			this.traceId = traceId;
			return this;
		}

		public Builder operation(String operation) {
			this.operation = operation;
			return this;
		}

		public Builder actionType(ActionType actionType) {
			this.actionType = actionType;
			return this;
		}

		public Builder resource(String resourceType, String resourceId) {
			this.resourceType = resourceType;
			this.resourceId = resourceId;
			return this;
		}

		public Builder tag(String key, String value) {
			if (this.tags == null) {
				this.tags = new LinkedHashMap<>();
			}
			this.tags.put(key, value);
			return this;
		}

		public Builder tags(Map<String, String> tags) {
			if (tags != null && !tags.isEmpty()) {
				if (this.tags == null) {
					this.tags = new LinkedHashMap<>();
				}
				this.tags.putAll(tags);
			}
			return this;
		}

		public Builder clientIp(String clientIp) {
			this.clientIp = clientIp;
			return this;
		}

		public Builder userAgent(String userAgent) {
			this.userAgent = userAgent;
			return this;
		}

		public Builder http(String method, String requestUri, String queryString) {
			this.httpMethod = method;
			this.requestUri = requestUri;
			this.queryString = queryString;
			return this;
		}

		public Builder service(String serviceName, String instanceId) {
			this.serviceName = serviceName;
			this.instanceId = instanceId;
			return this;
		}

		public Builder requestPayload(Payload requestPayload) {
			this.requestPayload = requestPayload;
			return this;
		}

		public Builder responsePayload(Payload responsePayload) {
			this.responsePayload = responsePayload;
			return this;
		}

		public Builder outcome(Outcome outcome) {
			this.outcome = outcome;
			return this;
		}

		public Builder httpStatus(Integer httpStatus) {
			this.httpStatus = httpStatus;
			return this;
		}

		public Builder businessResult(BusinessResult businessResult) {
			this.businessResult = businessResult;
			return this;
		}

		public Builder error(ErrorInfo error) {
			this.error = error;
			return this;
		}

		public Builder durationMillis(long durationMillis) {
			this.durationMillis = durationMillis;
			return this;
		}

		public Builder transactional(boolean transactional) {
			this.transactional = transactional;
			return this;
		}

		public Builder rolledBack(boolean rolledBack) {
			this.rolledBack = rolledBack;
			return this;
		}

		/** 冻结为不可变记录。 */
		public AuditRecord build() {
			return new AuditRecord(this);
		}
	}

	// ---- getters ----

	public String getId() {
		return id;
	}

	public long getSeq() {
		return seq;
	}

	public Integer getSchemaVersion() {
		return schemaVersion;
	}

	public Instant getEventTime() {
		return eventTime;
	}

	public Instant getReceivedTime() {
		return receivedTime;
	}

	public String getZoneId() {
		return zoneId;
	}

	public String getPrincipalId() {
		return principalId;
	}

	public String getPrincipalName() {
		return principalName;
	}

	public PrincipalType getPrincipalType() {
		return principalType;
	}

	public String getTenantId() {
		return tenantId;
	}

	public String getSessionId() {
		return sessionId;
	}

	public String getAuthType() {
		return authType;
	}

	public String getTraceId() {
		return traceId;
	}

	public String getOperation() {
		return operation;
	}

	public ActionType getActionType() {
		return actionType;
	}

	public String getResourceType() {
		return resourceType;
	}

	public String getResourceId() {
		return resourceId;
	}

	public Map<String, String> getTags() {
		return tags;
	}

	public String getClientIp() {
		return clientIp;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public String getHttpMethod() {
		return httpMethod;
	}

	public String getRequestUri() {
		return requestUri;
	}

	public String getQueryString() {
		return queryString;
	}

	public String getServiceName() {
		return serviceName;
	}

	public String getInstanceId() {
		return instanceId;
	}

	public Payload getRequestPayload() {
		return requestPayload;
	}

	public Payload getResponsePayload() {
		return responsePayload;
	}

	public Outcome getOutcome() {
		return outcome;
	}

	public Integer getHttpStatus() {
		return httpStatus;
	}

	public BusinessResult getBusinessResult() {
		return businessResult;
	}

	public ErrorInfo getError() {
		return error;
	}

	public long getDurationMillis() {
		return durationMillis;
	}

	public boolean isTransactional() {
		return transactional;
	}

	public boolean isRolledBack() {
		return rolledBack;
	}

	public String getPrevHash() {
		return prevHash;
	}

	public String getRecordHash() {
		return recordHash;
	}

	public String getSignature() {
		return signature;
	}

	@Override
	public String toString() {
		return "AuditRecord{id='" + id + "', seq=" + seq + ", operation='" + operation + "', actionType=" + actionType
			+ ", principalId='" + principalId + "', principalType=" + principalType + ", outcome=" + outcome
			+ ", httpStatus=" + httpStatus + ", durationMillis=" + durationMillis + ", uri='" + requestUri + "'}";
	}
}
