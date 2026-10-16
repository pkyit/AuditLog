package io.github.pkyit.auditlog.spring.properties;

import io.github.pkyit.auditlog.core.api.AuditMetricNames;
import io.github.pkyit.auditlog.core.mask.FieldMaskRule;
import io.github.pkyit.auditlog.core.mask.MaskType;
import io.github.pkyit.auditlog.core.mask.RegexMaskRule;
import io.github.pkyit.auditlog.core.pipeline.BackpressureStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 审计框架配置模型，绑定前缀 {@code audit.log}。
 *
 * <p>本类同时提供三类能力：配置项的承载与绑定、全局开关（采集通道、脱敏、哈希链、
 * 兜底日志）、以及订阅式嵌套配置。
 *
 * <p>所有字段均为可变属性，由 Spring Boot 在绑定期写入；绑定完成后配置即视为只读。
 * 派生结果（脱敏规则、小写化请求头名单）依赖这一不变量做记忆化，
 * 运行期修改字段不会使已缓存的派生结果失效。
 */
@ConfigurationProperties(prefix = AuditLogProperties.PREFIX)
public class AuditLogProperties {

	/** 配置前缀。 */
	public static final String PREFIX = "audit.log";

	/** 是否启用审计（总开关）。 */
	private boolean enabled = true;

	/** 服务名（写入每条记录，便于多服务汇聚后区分来源）。 */
	private String serviceName = "";

	/** 采集配置。 */
	private final Collector collector = new Collector();

	/** 脱敏配置。 */
	private final Masking masking = new Masking();

	/** 存储配置。 */
	private final Store store = new Store();

	/** 流水线与背压配置。 */
	private final Pipeline pipeline = new Pipeline();

	/** 哈希链与防篡改配置。 */
	private final Chain chain = new Chain();

	/**
	 * 事务回滚时是否丢弃审计记录。
	 *
	 * <p>默认 {@code true}：回滚意味着该次操作没有生效，为其留下记录会把"未发生的事"
	 * 写进审计库，污染审计结论（例如记录声称「管理员删除了用户 X」，而事务实际已回滚）。
	 *
	 * <p>设为 {@code false} 时改为写入一条 {@code Outcome=ROLLBACK}、
	 * {@code rolledBack=true} 的记录，用于「尝试性操作」审计；
	 * 消费方必须据此理解为「尝试未生效」。
	 */
	private boolean dropOnRollback = true;

	/** 本地兜底日志（WAL）配置。 */
	private final Journal journal = new Journal();

	/** 主体/IP/租户解析配置。 */
	private final Identity identity = new Identity();

	/** 启动期校验配置。 */
	private final Validation validation = new Validation();

	/** Actuator 集成配置。 */
	private final Actuator actuator = new Actuator();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getServiceName() {
		return serviceName;
	}

	public void setServiceName(String serviceName) {
		this.serviceName = serviceName;
	}

	public Collector getCollector() {
		return collector;
	}

	public Masking getMasking() {
		return masking;
	}

	public Store getStore() {
		return store;
	}

	public Pipeline getPipeline() {
		return pipeline;
	}

	public Chain getChain() {
		return chain;
	}

	public boolean isDropOnRollback() {
		return dropOnRollback;
	}

	public void setDropOnRollback(boolean dropOnRollback) {
		this.dropOnRollback = dropOnRollback;
	}

	public Journal getJournal() {
		return journal;
	}

	public Identity getIdentity() {
		return identity;
	}

	public Validation getValidation() {
		return validation;
	}

	public Actuator getActuator() {
		return actuator;
	}

	// ------------------------------------------------------------------

	/** 采集配置：边界通道与语义通道。 */
	public static class Collector {

		/**
		 * 边界通道（Servlet Filter）。
		 *
		 * <p>覆盖语义通道无法观测的请求：认证失败、403、404、参数绑定失败等
		 * 未进入 Handler 的路径，这些事件恰恰是审计最需要留痕的部分。
		 */
		private final Boundary boundary = new Boundary();

		/** 语义通道（HandlerAdapter）：提供操作名、动作类型、资源、耗时等审计语义。 */
		private final Method method = new Method();

		public Boundary getBoundary() {
			return boundary;
		}

		public Method getMethod() {
			return method;
		}
	}

	/** 边界通道配置。 */
	public static class Boundary {

		private boolean enabled = true;

		/**
		 * 包含的 URL 模式（Ant 风格）。
		 *
		 * <p>默认匹配所有路径。默认值不可收缩为单层模式：Ant 语法中 {@code /*} 只匹配
		 * 一层路径（如 {@code /a}），{@code /user/query} 这类多层路径不会命中，
		 * 结果是过滤器静默地不采集任何业务请求。
		 */
		private List<String> includePatterns = new ArrayList<>(List.of("/**"));

		/**
		 * 排除的 URL 模式（优先于 include）。默认排除静态资源与监控端点，避免审计噪音。
		 *
		 * <p>Ant 通配符 {@code *} 不跨目录分隔符：{@code *.css} 只匹配 {@code /app.css}
		 * 这类单层路径，不会匹配 {@code /static/app.css}。因此必须使用带多层目录通配前缀的
		 * {@code /**}{@code /*.css} 形式，否则子目录下的静态资源会全部进入审计。
		 */
		private List<String> excludePatterns = new ArrayList<>(List.of(
			"/**/*.css", "/**/*.js", "/**/*.png", "/**/*.jpg", "/**/*.jpeg", "/**/*.gif",
			"/**/*.ico", "/**/*.svg", "/**/*.woff", "/**/*.woff2",
			"/favicon.ico", "/actuator/**", "/error"));

		/**
		 * 过滤器顺序。
		 *
		 * <p>必须严格小于 Spring Security 过滤链的 order（Boot 默认 {@code -100}），
		 * 否则审计过滤器会排在 Security 之后：未认证请求在 Security 层即被拦截，
		 * 不会经过审计过滤器，认证失败事件漏采，而覆盖该场景正是边界通道存在的首要理由。
		 *
		 * <p>相同 order 值不保证先后顺序（取决于注册顺序，不同 Boot 版本可能翻转），
		 * 因此取 {@code -150} 而不是 {@code -100}。
		 */
		private int order = -150;

		/** 是否记录未匹配到 Handler 的请求（404）。 */
		private boolean recordUnmatched = true;

		/**
		 * 请求体缓存上限（字节）。
		 *
		 * <p>超过上限时审计只保留前 N 字节并标记 truncated，业务侧仍能读到完整报文
		 * （包装器为"透传 + 旁路缓存"，不截断业务读取）。
		 */
		private int maxCachedBodyBytes = 64 * 1024;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public List<String> getIncludePatterns() {
			return includePatterns;
		}

		public void setIncludePatterns(List<String> includePatterns) {
			this.includePatterns = includePatterns;
		}

		public List<String> getExcludePatterns() {
			return excludePatterns;
		}

		public void setExcludePatterns(List<String> excludePatterns) {
			this.excludePatterns = excludePatterns;
		}

		public int getOrder() {
			return order;
		}

		public void setOrder(int order) {
			this.order = order;
		}

		public boolean isRecordUnmatched() {
			return recordUnmatched;
		}

		public void setRecordUnmatched(boolean recordUnmatched) {
			this.recordUnmatched = recordUnmatched;
		}

		public int getMaxCachedBodyBytes() {
			return maxCachedBodyBytes;
		}

		public void setMaxCachedBodyBytes(int maxCachedBodyBytes) {
			this.maxCachedBodyBytes = maxCachedBodyBytes;
		}
	}

	/** 语义通道配置。 */
	public static class Method {

		private boolean enabled = true;

		/**
		 * 是否序列化方法实参。
		 *
		 * <p>默认关闭：序列化任意 Java 参数会触发懒加载异常、循环引用、超大对象，
		 * 并夹带框架参数噪音。默认改用请求体/查询串的稳定快照，仅在确有需要时开启。
		 */
		private boolean serializeArguments = false;

		/** 参数序列化的单值最大字符数。 */
		private int maxArgumentLength = 2048;

		/** 是否记录响应体（默认关闭：响应易含大对象与敏感数据）。 */
		private boolean captureResponseBody = false;

		/** 动作类型解析：注解未标注时使用的默认值。 */
		private String defaultActionType = "UNKNOWN";

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public boolean isSerializeArguments() {
			return serializeArguments;
		}

		public void setSerializeArguments(boolean serializeArguments) {
			this.serializeArguments = serializeArguments;
		}

		public int getMaxArgumentLength() {
			return maxArgumentLength;
		}

		public void setMaxArgumentLength(int maxArgumentLength) {
			this.maxArgumentLength = maxArgumentLength;
		}

		public boolean isCaptureResponseBody() {
			return captureResponseBody;
		}

		public void setCaptureResponseBody(boolean captureResponseBody) {
			this.captureResponseBody = captureResponseBody;
		}

		public String getDefaultActionType() {
			return defaultActionType;
		}

		public void setDefaultActionType(String defaultActionType) {
			this.defaultActionType = defaultActionType;
		}
	}

	/** 脱敏配置。 */
	public static class Masking {

		/**
		 * 是否启用自定义脱敏（字段规则 + 内容正则 + 追加敏感头）。
		 *
		 * <p>关闭后载荷与查询串按原文记录，便于内网排查；是否采集请求头、查询串
		 * 仍分别由 {@link #captureQueryString} 与各自的采集开关决定。
		 *
		 * <p>该开关关不掉安全底线：{@code Authorization}、{@code Cookie}、
		 * {@code X-API-Key} 等凭据类请求头始终会被打码（见
		 * {@code RuleBasedAuditMasker.MANDATORY_SENSITIVE_HEADERS}）。
		 * 凭据名单不受配置覆盖，否则一次配置失误即可把令牌写入审计库。
		 */
		private boolean enabled = true;

		/** 请求体最大记录字符数；超出部分被截断，不阻断采集。 */
		private int maxBodyLength = 4096;

		/**
		 * 序列化最大深度；超出该深度的层级不再展开，避免深层结构带来的栈与耗时开销。
		 */
		private int maxDepth = 8;

		/**
		 * 是否记录异常堆栈。
		 *
		 * <p>默认关闭：堆栈会暴露内部类名与文件路径，且显著放大记录体积。
		 */
		private boolean includeStackTrace = false;

		/** 异常消息最大长度；超出部分被截断。 */
		private int maxErrorMessageLength = 1024;

		/** 额外需要脱敏的请求头，大小写不敏感；与不可覆盖的核心凭据名单合并生效。 */
		private Set<String> sensitiveHeaders = new LinkedHashSet<>();

		/** 字段路径脱敏规则，形如 {@code $.user.password}、{@code **.idCard}。 */
		private List<String> sensitiveFields = new ArrayList<>(List.of(
			"$.password", "$.oldPassword", "$.newPassword", "$.confirmPassword",
			"$.token", "$.accessToken", "$.refreshToken", "$.secret", "$.apiKey",
			"$.idCard", "$.idNumber", "$.bankCard", "$.cardNo", "$.cvv", "$.mobile", "$.phone"));

		/** 字段路径 → 脱敏策略，例如 {@code $.mobile=PARTIAL}。 */
		private List<String> fieldRuleOverrides = new ArrayList<>();

		/** 是否启用内置内容正则规则（手机号/身份证/银行卡/邮箱/Bearer 令牌）。 */
		private boolean regexRulesEnabled = true;

		/** 额外正则脱敏规则，格式 {@code name:regex[:策略[:保留前缀:保留后缀]]}。 */
		private List<String> extraRegexRules = new ArrayList<>();

		/** 是否记录查询串（URL 参数）。 */
		private boolean captureQueryString = true;

		/**
		 * 派生结果缓存。
		 *
		 * <p>这些字段不是配置项，不参与绑定，只用于把采集路径上的重复计算降为一次计算。
		 * 配置在 {@code @ConfigurationProperties} 绑定完成后不再变化，因此缓存安全且语义中性。
		 * 用 {@code volatile} 配合同步块发布，保证多线程下只计算一次且安全可见。
		 */
		private volatile List<FieldMaskRule> fieldRulesCache;
		private volatile List<RegexMaskRule> regexRulesCache;
		private volatile Set<String> sensitiveHeadersLowercasedCache;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public int getMaxBodyLength() {
			return maxBodyLength;
		}

		public void setMaxBodyLength(int maxBodyLength) {
			this.maxBodyLength = maxBodyLength;
		}

		public int getMaxDepth() {
			return maxDepth;
		}

		public void setMaxDepth(int maxDepth) {
			this.maxDepth = maxDepth;
		}

		public boolean isIncludeStackTrace() {
			return includeStackTrace;
		}

		public void setIncludeStackTrace(boolean includeStackTrace) {
			this.includeStackTrace = includeStackTrace;
		}

		public int getMaxErrorMessageLength() {
			return maxErrorMessageLength;
		}

		public void setMaxErrorMessageLength(int maxErrorMessageLength) {
			this.maxErrorMessageLength = maxErrorMessageLength;
		}

		public Set<String> getSensitiveHeaders() {
			return sensitiveHeaders;
		}

		public void setSensitiveHeaders(Set<String> sensitiveHeaders) {
			this.sensitiveHeaders = sensitiveHeaders;
		}

		public List<String> getSensitiveFields() {
			return sensitiveFields;
		}

		public void setSensitiveFields(List<String> sensitiveFields) {
			this.sensitiveFields = sensitiveFields;
		}

		public List<String> getFieldRuleOverrides() {
			return fieldRuleOverrides;
		}

		public void setFieldRuleOverrides(List<String> fieldRuleOverrides) {
			this.fieldRuleOverrides = fieldRuleOverrides;
		}

		public boolean isRegexRulesEnabled() {
			return regexRulesEnabled;
		}

		public void setRegexRulesEnabled(boolean regexRulesEnabled) {
			this.regexRulesEnabled = regexRulesEnabled;
		}

		public List<String> getExtraRegexRules() {
			return extraRegexRules;
		}

		public void setExtraRegexRules(List<String> extraRegexRules) {
			this.extraRegexRules = extraRegexRules;
		}

		public boolean isCaptureQueryString() {
			return captureQueryString;
		}

		public void setCaptureQueryString(boolean captureQueryString) {
			this.captureQueryString = captureQueryString;
		}

		/**
		 * 小写化的敏感请求头集合。
		 *
		 * <p>结果被记忆化：本方法在采集路径上按请求调用，而配置在
		 * {@code @ConfigurationProperties} 绑定完成后不再变化。
		 *
		 * @return 不可变集合
		 */
		public Set<String> sensitiveHeadersLowercased() {
			Set<String> cached = this.sensitiveHeadersLowercasedCache;
			if (cached != null) {
				return cached;
			}
			synchronized (this) {
				if (this.sensitiveHeadersLowercasedCache != null) {
					return this.sensitiveHeadersLowercasedCache;
				}
				Set<String> result = new LinkedHashSet<>();
				for (String header : sensitiveHeaders) {
					if (header != null && !header.isBlank()) {
						result.add(header.toLowerCase(Locale.ROOT));
					}
				}
				Set<String> immutable = Set.copyOf(result);
				this.sensitiveHeadersLowercasedCache = immutable;
				return immutable;
			}
		}

		/**
		 * 解析为字段脱敏规则，并叠加 overrides 指定的策略。
		 *
		 * <p>结果被记忆化：本方法在采集路径上按请求多次调用，而配置在
		 * {@code @ConfigurationProperties} 绑定完成后不再变化。缺少记忆化时，
		 * 每次调用都会重建列表并重新编译正则，使 {@code FieldMaskRule}、
		 * {@code RegexMaskRule}、{@code Pattern} 出现在分配热点中。
		 * 记忆化不改变任何语义，只把重复计算降为一次。
		 *
		 * @return 不可变规则列表
		 */
		public List<FieldMaskRule> toFieldRules() {
			List<FieldMaskRule> cached = this.fieldRulesCache;
			if (cached != null) {
				return cached;
			}
			synchronized (this) {
				if (this.fieldRulesCache != null) {
					return this.fieldRulesCache;
				}
				java.util.Map<String, MaskType> overrides = new java.util.LinkedHashMap<>();
				for (String override : fieldRuleOverrides) {
					if (override == null || !override.contains("=")) {
						continue;
					}
					int index = override.indexOf('=');
					String path = override.substring(0, index).trim();
					String strategy = override.substring(index + 1).trim().toUpperCase(Locale.ROOT);
					try {
						overrides.put(path, MaskType.valueOf(strategy));
					}
					catch (IllegalArgumentException ignored) {
						// 非法策略名忽略，由启动校验负责告警
					}
				}
				List<FieldMaskRule> rules = new ArrayList<>();
				java.util.Set<String> seen = new java.util.LinkedHashSet<>();
				for (String field : sensitiveFields) {
					if (field == null || field.isBlank()) {
						continue;
					}
					String path = field.trim();
					if (!seen.add(path)) {
						continue;
					}
					MaskType type = overrides.getOrDefault(path, MaskType.FULL);
					rules.add(new FieldMaskRule(path, type, '*', 0, 0));
				}
				overrides.forEach((path, type) -> {
					if (seen.add(path)) {
						rules.add(new FieldMaskRule(path, type, '*', 0, 0));
					}
				});
				List<FieldMaskRule> immutable = List.copyOf(rules);
				this.fieldRulesCache = immutable;
				return immutable;
			}
		}

		/**
		 * 解析正则脱敏规则（内置 + 额外）。
		 *
		 * <p>结果被记忆化：不记忆化时每次调用都要让 {@code RegexMaskRule.of} 执行
		 * {@code Pattern.compile}，在采集路径上重复编译内置规则是纯粹的浪费。
		 *
		 * @return 不可变规则列表
		 */
		public List<RegexMaskRule> toRegexRules() {
			List<RegexMaskRule> cached = this.regexRulesCache;
			if (cached != null) {
				return cached;
			}
			synchronized (this) {
				if (this.regexRulesCache != null) {
					return this.regexRulesCache;
				}
				List<RegexMaskRule> rules = new ArrayList<>();
				if (regexRulesEnabled) {
					rules.add(RegexMaskRule.bearerToken());
					rules.add(RegexMaskRule.idCard());
					rules.add(RegexMaskRule.bankCard());
					rules.add(RegexMaskRule.mobile());
					rules.add(RegexMaskRule.email());
				}
				for (String definition : extraRegexRules) {
					if (definition == null || definition.isBlank()) {
						continue;
					}
					String[] parts = definition.split(":", 5);
					if (parts.length < 2) {
						continue;
					}
					try {
						MaskType type = parts.length > 2 && !parts[2].isBlank()
							? MaskType.valueOf(parts[2].trim().toUpperCase(Locale.ROOT))
							: MaskType.FULL;
						int prefix = parts.length > 3 ? Integer.parseInt(parts[3].trim()) : 0;
						int suffix = parts.length > 4 ? Integer.parseInt(parts[4].trim()) : 0;
						rules.add(RegexMaskRule.of(parts[0].trim(), parts[1], type, prefix, suffix));
					}
					catch (RuntimeException ignored) {
						// 非法规则忽略，由启动校验负责告警
					}
				}
				List<RegexMaskRule> immutable = List.copyOf(rules);
				this.regexRulesCache = immutable;
				return immutable;
			}
		}
	}

	/** 存储配置。 */
	public static class Store {

		/**
		 * 存储类型：{@code logging} / {@code json-file} / {@code jdbc} / {@code custom}。
		 *
		 * <p>默认 {@code logging} 不落盘，仅用于"引入依赖不影响现网环境"；
		 * 该取值下启动期会告警提示审计记录非持久化。
		 */
		private String type = "logging";

		/** json-file 存储的目录。 */
		private String directory = "./logs/audit";

		/** 单个审计文件最大字节数。 */
		private long maxFileSize = 128L * 1024 * 1024;

		/** 保留的历史文件数。 */
		private int maxHistory = 30;

		/** 文件名前缀（建议按实例分片，例如含 {@code instanceId}）。 */
		private String filePrefix = "audit";

		/** jdbc 存储的表名。 */
		private String tableName = "audit_log";

		/**
		 * 是否随应用启动自动建表。
		 *
		 * <p>默认关闭：建表需要 DDL 权限，部分环境不授予该权限。生产应由迁移脚本管理表结构。
		 */
		private boolean autoCreateTable = false;

		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}

		public String getDirectory() {
			return directory;
		}

		public void setDirectory(String directory) {
			this.directory = directory;
		}

		public long getMaxFileSize() {
			return maxFileSize;
		}

		public void setMaxFileSize(long maxFileSize) {
			this.maxFileSize = maxFileSize;
		}

		public int getMaxHistory() {
			return maxHistory;
		}

		public void setMaxHistory(int maxHistory) {
			this.maxHistory = maxHistory;
		}

		public String getFilePrefix() {
			return filePrefix;
		}

		public void setFilePrefix(String filePrefix) {
			this.filePrefix = filePrefix;
		}

		public String getTableName() {
			return tableName;
		}

		public void setTableName(String tableName) {
			this.tableName = tableName;
		}

		public boolean isAutoCreateTable() {
			return autoCreateTable;
		}

		public void setAutoCreateTable(boolean autoCreateTable) {
			this.autoCreateTable = autoCreateTable;
		}
	}

	/** 流水线配置。 */
	public static class Pipeline {

		/** 队列容量。 */
		private int queueCapacity = 8192;

		/** 单批最大记录数。 */
		private int batchSize = 100;

		/** 组批等待毫秒数。 */
		private long batchTimeoutMillis = 200L;

		/** 消费者线程数。启用哈希链时被强制为 1，链的追加必须串行。 */
		private int consumerThreads = 1;

		/** 背压策略。 */
		private BackpressureStrategy strategy = BackpressureStrategy.SPILL;

		/** BLOCK 策略最长阻塞毫秒数。 */
		private long blockTimeoutMillis = 2000L;

		/** 投递失败最大重试次数。 */
		private int maxRetries = 3;

		/** 重试初始退避毫秒数（指数增长）。 */
		private long retryBackoffMillis = 200L;

		/** 最近记录缓存条数（用于链校验与诊断端点）。 */
		private int recentCacheSize = 10_000;

		public int getQueueCapacity() {
			return queueCapacity;
		}

		public void setQueueCapacity(int queueCapacity) {
			this.queueCapacity = queueCapacity;
		}

		public int getBatchSize() {
			return batchSize;
		}

		public void setBatchSize(int batchSize) {
			this.batchSize = batchSize;
		}

		public long getBatchTimeoutMillis() {
			return batchTimeoutMillis;
		}

		public void setBatchTimeoutMillis(long batchTimeoutMillis) {
			this.batchTimeoutMillis = batchTimeoutMillis;
		}

		public int getConsumerThreads() {
			return consumerThreads;
		}

		public void setConsumerThreads(int consumerThreads) {
			this.consumerThreads = consumerThreads;
		}

		public BackpressureStrategy getStrategy() {
			return strategy;
		}

		public void setStrategy(BackpressureStrategy strategy) {
			this.strategy = strategy;
		}

		public long getBlockTimeoutMillis() {
			return blockTimeoutMillis;
		}

		public void setBlockTimeoutMillis(long blockTimeoutMillis) {
			this.blockTimeoutMillis = blockTimeoutMillis;
		}

		public int getMaxRetries() {
			return maxRetries;
		}

		public void setMaxRetries(int maxRetries) {
			this.maxRetries = maxRetries;
		}

		public long getRetryBackoffMillis() {
			return retryBackoffMillis;
		}

		public void setRetryBackoffMillis(long retryBackoffMillis) {
			this.retryBackoffMillis = retryBackoffMillis;
		}

		public int getRecentCacheSize() {
			return recentCacheSize;
		}

		public void setRecentCacheSize(int recentCacheSize) {
			this.recentCacheSize = recentCacheSize;
		}
	}

	/** 哈希链配置。 */
	public static class Chain {

		/** 是否启用哈希链（默认关闭：有性能成本，需显式开启）。 */
		private boolean enabled = false;

		/**
		 * HMAC 密钥。
		 *
		 * <p>为空时退化为无密钥 SHA-256 链：能发现意外损坏，但无法抵御拥有写权限的攻击者。
		 * 生产环境必须由密钥管理服务或环境变量注入，不得写入配置文件与镜像。
		 */
		private String secret = "";

		/**
		 * 链状态持久化文件路径。
		 *
		 * <p>链头（{@code head}）是内存态。若重启后不恢复，新记录的 {@code prevHash}
		 * 无法与存储中最后一条记录的 {@code recordHash} 衔接，
		 * 任何跨重启的链校验都会在重启边界误报"被篡改"。
		 *
		 * <p>该文件以"临时文件 + 原子改名"写入，保证读到的永远是完整状态。
		 * 文件丢失时框架会从存储回读链尾恢复（见 {@code AuditStore#recoverChainState}）。
		 */
		private String stateFile = "./logs/audit-chain-state.properties";

		public String getStateFile() {
			return stateFile;
		}

		public void setStateFile(String stateFile) {
			this.stateFile = stateFile;
		}

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getSecret() {
			return secret;
		}

		public void setSecret(String secret) {
			this.secret = secret;
		}
	}

	/** 本地兜底日志配置。 */
	public static class Journal {

		/** 是否启用。 */
		private boolean enabled = true;

		/** 目录。 */
		private String directory = "./logs/audit-journal";

		/** 单文件最大字节数。 */
		private long maxFileSize = 64L * 1024 * 1024;

		/** 保留片段数。 */
		private int maxHistory = 20;

		/** WAL 写队列容量。请求线程只入队，不执行落盘。 */
		private int queueCapacity = 8192;

		/**
		 * WAL 内存降级缓冲上限，写队列满时承接记录。
		 *
		 * <p>该缓冲也满后记录会被丢弃并记入指标，因此取值决定磁盘持续不可用时能承受的突发量。
		 */
		private int memoryBufferLimit = 10_000;

		/** 启动时是否自动重放未投递记录。 */
		private boolean replayOnStartup = true;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getDirectory() {
			return directory;
		}

		public void setDirectory(String directory) {
			this.directory = directory;
		}

		public long getMaxFileSize() {
			return maxFileSize;
		}

		public void setMaxFileSize(long maxFileSize) {
			this.maxFileSize = maxFileSize;
		}

		public int getMaxHistory() {
			return maxHistory;
		}

		public void setMaxHistory(int maxHistory) {
			this.maxHistory = maxHistory;
		}

		public int getQueueCapacity() {
			return queueCapacity;
		}

		public void setQueueCapacity(int queueCapacity) {
			this.queueCapacity = queueCapacity;
		}

		public int getMemoryBufferLimit() {
			return memoryBufferLimit;
		}

		public void setMemoryBufferLimit(int memoryBufferLimit) {
			this.memoryBufferLimit = memoryBufferLimit;
		}

		public boolean isReplayOnStartup() {
			return replayOnStartup;
		}

		public void setReplayOnStartup(boolean replayOnStartup) {
			this.replayOnStartup = replayOnStartup;
		}
	}

	/** 身份解析配置。 */
	public static class Identity {

		/**
		 * 是否允许从请求头解析主体。
		 *
		 * <p>默认关闭：请求头可被客户端伪造，开启后必须确保应用位于可信网关之后。
		 */
		private boolean headerPrincipalEnabled = false;

		/** 主体 ID 请求头名。 */
		private String principalIdHeader = "X-User-Id";

		/** 主体名请求头名。 */
		private String principalNameHeader = "X-User-Name";

		/** 租户 ID 请求头名。 */
		private String tenantIdHeader = "X-Tenant-Id";

		/** 链路 ID 请求头名（与主流 APM 约定一致）。 */
		private String traceIdHeader = "traceparent";

		/** 备用链路 ID 请求头。 */
		private String fallbackTraceIdHeader = "X-Trace-Id";

		/**
		 * 是否信任代理头（X-Forwarded-For 等）。
		 *
		 * <p>默认关闭：代理头可被客户端伪造。开启后仅当直连对端落在
		 * {@link #trustedProxies} 内时才采纳，否则回退到直连地址。
		 */
		private boolean trustProxyHeaders = false;

		/** 可信代理网段（CIDR 或 IP 前缀），例如 {@code 10.0.0.0/8}、{@code 172.17.}。 */
		private List<String> trustedProxies = new ArrayList<>();

		public boolean isHeaderPrincipalEnabled() {
			return headerPrincipalEnabled;
		}

		public void setHeaderPrincipalEnabled(boolean headerPrincipalEnabled) {
			this.headerPrincipalEnabled = headerPrincipalEnabled;
		}

		public String getPrincipalIdHeader() {
			return principalIdHeader;
		}

		public void setPrincipalIdHeader(String principalIdHeader) {
			this.principalIdHeader = principalIdHeader;
		}

		public String getPrincipalNameHeader() {
			return principalNameHeader;
		}

		public void setPrincipalNameHeader(String principalNameHeader) {
			this.principalNameHeader = principalNameHeader;
		}

		public String getTenantIdHeader() {
			return tenantIdHeader;
		}

		public void setTenantIdHeader(String tenantIdHeader) {
			this.tenantIdHeader = tenantIdHeader;
		}

		public String getTraceIdHeader() {
			return traceIdHeader;
		}

		public void setTraceIdHeader(String traceIdHeader) {
			this.traceIdHeader = traceIdHeader;
		}

		public String getFallbackTraceIdHeader() {
			return fallbackTraceIdHeader;
		}

		public void setFallbackTraceIdHeader(String fallbackTraceIdHeader) {
			this.fallbackTraceIdHeader = fallbackTraceIdHeader;
		}

		public boolean isTrustProxyHeaders() {
			return trustProxyHeaders;
		}

		public void setTrustProxyHeaders(boolean trustProxyHeaders) {
			this.trustProxyHeaders = trustProxyHeaders;
		}

		public List<String> getTrustedProxies() {
			return trustedProxies;
		}

		public void setTrustedProxies(List<String> trustedProxies) {
			this.trustedProxies = trustedProxies;
		}
	}

	/** 启动校验配置。 */
	public static class Validation {

		/**
		 * 注解自相矛盾时是否让应用启动失败。
		 *
		 * <p>该开关针对使用者的配置错误（注解写在非 Handler 方法上、方法被排除等）。
		 * 这类错误必须尽早暴露，否则会一直潜伏到审计对账时才发现。
		 */
		private boolean failOnMismatch = true;

		/** 运行期出现未匹配的 Handler 时是否打印一次告警。 */
		private boolean warnOnUnmatchedHandler = true;

		/** 使用非持久化存储时是否告警。 */
		private boolean warnOnNonCompliantStore = true;

		public boolean isFailOnMismatch() {
			return failOnMismatch;
		}

		public void setFailOnMismatch(boolean failOnMismatch) {
			this.failOnMismatch = failOnMismatch;
		}

		public boolean isWarnOnUnmatchedHandler() {
			return warnOnUnmatchedHandler;
		}

		public void setWarnOnUnmatchedHandler(boolean warnOnUnmatchedHandler) {
			this.warnOnUnmatchedHandler = warnOnUnmatchedHandler;
		}

		public boolean isWarnOnNonCompliantStore() {
			return warnOnNonCompliantStore;
		}

		public void setWarnOnNonCompliantStore(boolean warnOnNonCompliantStore) {
			this.warnOnNonCompliantStore = warnOnNonCompliantStore;
		}
	}

	/** Actuator 集成配置。 */
	public static class Actuator {

		/** 是否注册健康指示器。 */
		private boolean healthEnabled = true;

		/** 是否注册指标端点。 */
		private boolean metricsEndpointEnabled = true;

		/** 是否注册管理端点（收录清单/链校验）。 */
		private boolean managementEndpointEnabled = true;

		/** 队列积压达到该值时健康状态为 DOWN。 */
		private int queueDepthDownThreshold = 100_000;

		public boolean isHealthEnabled() {
			return healthEnabled;
		}

		public void setHealthEnabled(boolean healthEnabled) {
			this.healthEnabled = healthEnabled;
		}

		public boolean isMetricsEndpointEnabled() {
			return metricsEndpointEnabled;
		}

		public void setMetricsEndpointEnabled(boolean metricsEndpointEnabled) {
			this.metricsEndpointEnabled = metricsEndpointEnabled;
		}

		public boolean isManagementEndpointEnabled() {
			return managementEndpointEnabled;
		}

		public void setManagementEndpointEnabled(boolean managementEndpointEnabled) {
			this.managementEndpointEnabled = managementEndpointEnabled;
		}

		public int getQueueDepthDownThreshold() {
			return queueDepthDownThreshold;
		}

		public void setQueueDepthDownThreshold(int queueDepthDownThreshold) {
			this.queueDepthDownThreshold = queueDepthDownThreshold;
		}
	}

	/**
	 * 归一化指标名。
	 *
	 * @param name 指标名；为 {@code null} 时回退为 {@link AuditMetricNames#COLLECTED}
	 * @return 可安全用于指标注册的名称
	 */
	public static String metricName(String name) {
		return name == null ? AuditMetricNames.COLLECTED : name;
	}
}
