package io.github.pkyit.auditlog.spring.store;

import io.github.pkyit.auditlog.core.api.AuditCodec;
import io.github.pkyit.auditlog.core.api.AuditMetricNames;
import io.github.pkyit.auditlog.core.api.AuditMetrics;
import io.github.pkyit.auditlog.core.api.AuditStore;
import io.github.pkyit.auditlog.core.model.AuditRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;

/**
 * 审计存储的 JDBC 实现：全部记录写入单张审计表。
 *
 * <p>与框架的契约：
 * <ul>
 *   <li><b>只增不改</b>：只执行 INSERT，不提供 UPDATE/DELETE 方法（不变量 I4）；</li>
 *   <li><b>幂等</b>：以 {@code id} 为主键，重复投递（WAL 重放）不会产生重复记录；</li>
 *   <li><b>失败即抛</b>：批量写入失败时抛异常，由流水线重试 / 降级 WAL / 进死信，
 *       绝不静默吞掉。</li>
 * </ul>
 *
 * <p>建议的库表权限（运维层面）：应用账号仅授予 {@code INSERT} 与 {@code SELECT}，
 * 不授予 {@code UPDATE}/{@code DELETE}，从数据库层面保障「历史不可修改」。
 */
public class JdbcAuditStore implements AuditStore {

	private static final Logger log = LoggerFactory.getLogger(JdbcAuditStore.class);

	private static final String INSERT_SQL_TEMPLATE = """
		INSERT INTO %s (
		  id, seq, event_time, received_time, zone_id, principal_id, principal_name, principal_type,
		  tenant_id, session_id, auth_type, trace_id, operation, action_type, resource_type, resource_id,
		  client_ip, user_agent, http_method, request_uri, query_string, service_name, instance_id,
		  request_body, response_body, outcome, http_status, biz_code, biz_message,
		  error_type, error_message, duration_millis, transactional, rolled_back,
		  prev_hash, record_hash, signature, payload
		) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
		""";

	private final JdbcTemplate jdbcTemplate;
	private final DataSource dataSource;
	private final String tableName;
	private final AuditCodec codec;
	private final AuditMetrics metrics;
	private final String insertSql;
	private volatile boolean tableChecked;
	private volatile String largeTextType;

	public JdbcAuditStore(DataSource dataSource, String tableName, AuditCodec codec, AuditMetrics metrics) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.dataSource = dataSource;
		this.tableName = sanitizeTableName(tableName);
		this.codec = codec;
		this.metrics = metrics == null ? AuditMetrics.noop() : metrics;
		this.insertSql = String.format(INSERT_SQL_TEMPLATE, this.tableName);
	}

	/**
	 * 推导当前数据库的大文本列类型。
	 *
	 * <p>SQL 没有统一的大文本类型名：PostgreSQL、MySQL、H2 等使用 {@code TEXT}，
	 * 而 Oracle 与 DB2 只认 {@code CLOB}。DDL 中硬编码任一种都会让另一类数据库建表失败，
	 * 且失败发生在启动期并会中断整个宿主应用，代价过大。
	 *
	 * <p>推导失败时回退 {@code TEXT}（覆盖面更广），并记录实际选用的类型，
	 * 使方言选择可被排查而不是隐式发生。
	 */
	private String resolveLargeTextType() {
		String cached = this.largeTextType;
		if (cached != null) {
			return cached;
		}
		String product = "";
		try (java.sql.Connection connection = dataSource.getConnection()) {
			java.sql.DatabaseMetaData metaData = connection.getMetaData();
			product = metaData.getDatabaseProductName();
		}
		catch (Exception error) {
			log.warn("[audit-log] 无法读取数据库产品名，大文本列回退为 TEXT：{}", error.toString());
		}
		String normalized = product == null ? "" : product.toLowerCase(java.util.Locale.ROOT);
		String type = normalized.contains("oracle") || normalized.contains("db2") ? "CLOB" : "TEXT";
		log.info("[audit-log] 审计表大文本列类型={}（数据库={}）", type,
			product == null || product.isBlank() ? "未知" : product);
		this.largeTextType = type;
		return type;
	}

	/**
	 * 批量写入记录。
	 *
	 * <p>{@code null} 与空集合直接返回；主键已存在的记录按投递成功处理
	 * （见 {@link #insertBatch}），因此 WAL 重放不会失败。
	 *
	 * @param records 待写入记录，可为 {@code null}
	 */
	@Override
	public void save(List<AuditRecord> records) {
		if (records == null || records.isEmpty()) {
			return;
		}
		insertBatch(records);
	}

	/**
	 * 批量写入，区分「重复」与「故障」两类失败。
	 *
	 * <p>两类失败必须区别对待，否则崩溃恢复会自伤：
	 * <ul>
	 *   <li><b>主键重复</b>：说明这批记录已经落库过（WAL 重放的正常情形），
	 *       必须视为成功；若把 batchUpdate 失败一律当作故障，重放已落库的记录
	 *       会重试到耗尽、整批进死信，且内存链头前进到"从未落库的哈希"上。</li>
	 *   <li><b>真实故障</b>（连接断开、约束错误等）：原样抛出，由流水线重试 → 降级 WAL → 死信。</li>
	 * </ul>
	 *
	 * @param records 待写入记录，非空（{@link #save} 已过滤 {@code null} 与空集合）
	 */
	private void insertBatch(List<AuditRecord> records) {
		try {
			jdbcTemplate.batchUpdate(insertSql, records, records.size(), this::bind);
			// 不递增 audit.store.delivered —— 该指标由 AuditPipeline 统一负责（见 ADR-013），
			// 存储侧重复计数会让指标翻倍，使"不丢数据"无法被证明。
			return;
		}
		catch (RuntimeException batchError) {
			if (!isDuplicate(batchError)) {
				throw batchError;
			}
			// 批量里含重复行，但无法从异常定位是哪些行：逐条重试以区分"已存在"与"真故障"
			log.debug("[audit-log] 批量写入命中主键重复，逐条区分：{}", batchError.getMessage());
		}
		int skipped = 0;
		for (AuditRecord record : records) {
			try {
				jdbcTemplate.update(insertSql, statement -> bind(statement, record));
			}
			catch (RuntimeException singleError) {
				if (isDuplicate(singleError)) {
					// 已存在 = 本次投递成功（幂等），不是故障
					skipped++;
					continue;
				}
				throw singleError;
			}
		}
		if (skipped > 0) {
			log.info("[audit-log] 重放时跳过 {} 条已存在的记录（幂等，非故障）", skipped);
		}
	}

	/** 绑定一条记录到 INSERT 语句（批量与单条路径共用）。 */
	private void bind(java.sql.PreparedStatement statement, AuditRecord record) throws java.sql.SQLException {
		int index = 1;
		statement.setString(index++, record.getId());
		statement.setLong(index++, record.getSeq());
		statement.setTimestamp(index++, toTimestamp(record.getEventTime()));
		statement.setTimestamp(index++, toTimestamp(record.getReceivedTime()));
		statement.setString(index++, record.getZoneId());
		statement.setString(index++, record.getPrincipalId());
		statement.setString(index++, record.getPrincipalName());
		statement.setString(index++, record.getPrincipalType() == null ? null : record.getPrincipalType().name());
		statement.setString(index++, record.getTenantId());
		statement.setString(index++, record.getSessionId());
		statement.setString(index++, record.getAuthType());
		statement.setString(index++, record.getTraceId());
		statement.setString(index++, record.getOperation());
		statement.setString(index++, record.getActionType() == null ? null : record.getActionType().name());
		statement.setString(index++, record.getResourceType());
		statement.setString(index++, record.getResourceId());
		statement.setString(index++, record.getClientIp());
		statement.setString(index++, record.getUserAgent());
		statement.setString(index++, record.getHttpMethod());
		statement.setString(index++, record.getRequestUri());
		statement.setString(index++, record.getQueryString());
		statement.setString(index++, record.getServiceName());
		statement.setString(index++, record.getInstanceId());
		statement.setString(index++, record.getRequestPayload() == null ? null
			: record.getRequestPayload().body());
		statement.setString(index++, record.getResponsePayload() == null ? null
			: record.getResponsePayload().body());
		statement.setString(index++, record.getOutcome() == null ? null : record.getOutcome().name());
		statement.setObject(index++, record.getHttpStatus());
		statement.setObject(index++, record.getBusinessResult() == null ? null
			: record.getBusinessResult().code());
		statement.setString(index++, record.getBusinessResult() == null ? null
			: record.getBusinessResult().message());
		statement.setString(index++, record.getError() == null ? null : record.getError().type());
		statement.setString(index++, record.getError() == null ? null : record.getError().message());
		statement.setLong(index++, record.getDurationMillis());
		statement.setBoolean(index++, record.isTransactional());
		statement.setBoolean(index++, record.isRolledBack());
		statement.setString(index++, record.getPrevHash());
		statement.setString(index++, record.getRecordHash());
		statement.setString(index++, record.getSignature());
		// 完整载荷（JSON Lines 格式）一并落库，保证即使表结构演进也能还原原始记录
		statement.setString(index, codec.encode(record));
	}

	/** 判断异常是否为唯一约束/主键冲突（跨数据库方言）。 */
	private static boolean isDuplicate(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof org.springframework.dao.DuplicateKeyException) {
				return true;
			}
			if (current instanceof java.sql.SQLIntegrityConstraintViolationException) {
				return true;
			}
			if (current instanceof java.sql.SQLException sql) {
				String state = sql.getSQLState();
				// 23505 = unique_violation (PostgreSQL/openGauss)；23000 = integrity_constraint (MySQL)
				if ("23505".equals(state) || "23000".equals(state)) {
					return true;
				}
			}
			// 部分驱动只把线索放在 message 里（如 H2）
			String message = current.getMessage();
			if (message != null) {
				String lower = message.toLowerCase(java.util.Locale.ROOT);
				if (lower.contains("duplicate") || lower.contains("unique constraint")
					|| lower.contains("unique index or primary key violation")) {
					return true;
				}
			}
			current = current.getCause() == current ? null : current.getCause();
		}
		return false;
	}

	/**
	 * 从存储回读链尾，用于链状态文件缺失时的兜底恢复。
	 *
	 * <p>记录自带 seq 与 record_hash，因此即使状态文件丢了，链也能续上 ——
	 * 不让恢复能力依赖单一文件。
	 *
	 * @return 最新一条记录的链状态；表为空或回读失败时返回 {@code null}，
	 *         后者按冷启动处理，仅记 WARN 日志而不向调用方抛出
	 */
	@Override
	public io.github.pkyit.auditlog.core.api.ChainState recoverChainState() {
		try {
			List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
				"SELECT seq, record_hash FROM " + tableName + " ORDER BY seq DESC LIMIT 1");
			if (rows.isEmpty()) {
				return null;
			}
			java.util.Map<String, Object> row = rows.get(0);
			Object seqValue = row.get("seq");
			Object hashValue = row.get("record_hash");
			if (seqValue == null) {
				return null;
			}
			String hash = hashValue == null ? null : String.valueOf(hashValue);
			return new io.github.pkyit.auditlog.core.api.ChainState(
				hash, ((Number) seqValue).longValue());
		}
		catch (RuntimeException error) {
			log.warn("[audit-log] 从存储回读链尾失败（将按冷启动处理）：{}", error.getMessage());
			return null;
		}
	}

	/** 存储名称，用于指标与日志标签；固定为 {@code jdbc}，不随实现类名变化。 */
	@Override
	public String getName() {
		return "jdbc";
	}

	/**
	 * 探测数据库连通性。
	 *
	 * <p>不向调用方抛出异常：探测失败时递增 {@link AuditMetricNames#STORE_UNHEALTHY}
	 * 后返回 {@code false}。
	 *
	 * @return {@code true} 表示 {@code SELECT 1} 执行成功
	 */
	@Override
	public boolean isHealthy() {
		try {
			jdbcTemplate.queryForObject("SELECT 1", Integer.class);
			return true;
		}
		catch (Throwable error) {
			metrics.increment(AuditMetricNames.STORE_UNHEALTHY);
			return false;
		}
	}

	/**
	 * 建表并创建常用查询索引（仅用于快速验证；生产环境请使用正式的数据库迁移脚本）。
	 *
	 * <p>检查与置位 {@code tableChecked} 非原子：并发调用可能重复执行 DDL，
	 * 该竞态可接受，因为语句均带 {@code IF NOT EXISTS}，重复执行不报错。
	 */
	public void createTableIfAbsent() {
		if (tableChecked) {
			return;
		}
		String ddl = """
 CREATE TABLE IF NOT EXISTS %1$s (
			  id VARCHAR(96) NOT NULL,
			  seq BIGINT NOT NULL,
			  event_time TIMESTAMP NULL,
			  received_time TIMESTAMP NULL,
			  zone_id VARCHAR(64) NULL,
			  principal_id VARCHAR(255) NULL,
			  principal_name VARCHAR(255) NULL,
			  principal_type VARCHAR(32) NULL,
			  tenant_id VARCHAR(128) NULL,
			  session_id VARCHAR(128) NULL,
			  auth_type VARCHAR(64) NULL,
			  trace_id VARCHAR(128) NULL,
			  operation VARCHAR(255) NULL,
			  action_type VARCHAR(32) NULL,
			  resource_type VARCHAR(128) NULL,
			  resource_id VARCHAR(255) NULL,
			  client_ip VARCHAR(64) NULL,
			  user_agent VARCHAR(512) NULL,
			  http_method VARCHAR(16) NULL,
			  request_uri VARCHAR(1024) NULL,
			  query_string VARCHAR(2048) NULL,
			  service_name VARCHAR(128) NULL,
			  instance_id VARCHAR(64) NULL,
			  request_body %2$s NULL,
			  response_body %2$s NULL,
			  outcome VARCHAR(16) NULL,
			  http_status INT NULL,
			  biz_code INT NULL,
			  biz_message VARCHAR(1024) NULL,
			  error_type VARCHAR(255) NULL,
			  error_message VARCHAR(2048) NULL,
			  duration_millis BIGINT NULL,
			  transactional BOOLEAN NULL,
			  rolled_back BOOLEAN NULL,
			  prev_hash VARCHAR(128) NULL,
			  record_hash VARCHAR(128) NULL,
			  signature VARCHAR(128) NULL,
			  payload %2$s NOT NULL,
			  PRIMARY KEY (id)
			)
			""".formatted(tableName, resolveLargeTextType());
		jdbcTemplate.execute(ddl);
		// 常用审计查询维度建索引：按主体、时间、动作类型、链路
		safeExecute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_principal ON " + tableName
			+ " (principal_id, event_time)");
		safeExecute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_action ON " + tableName
			+ " (action_type, event_time)");
		safeExecute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_trace ON " + tableName + " (trace_id)");
		safeExecute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_seq ON " + tableName + " (seq)");
		tableChecked = true;
		log.info("[audit-log] 审计表已就绪 table={}", tableName);
	}

	private void safeExecute(String sql) {
		try {
			jdbcTemplate.execute(sql);
		}
		catch (Throwable error) {
			log.warn("[audit-log] 索引创建失败（不影响功能）：{}", error.toString());
		}
	}

	private static Timestamp toTimestamp(java.time.Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	/** 表名白名单校验，防止 SQL 注入（配置项不应成为注入入口）。 */
	private static String sanitizeTableName(String tableName) {
		String candidate = tableName == null || tableName.isBlank() ? "audit_log" : tableName.trim();
		if (!candidate.matches("[A-Za-z_][A-Za-z0-9_]*")) {
			throw new IllegalArgumentException("非法的审计表名（仅允许字母、数字、下划线）：" + tableName);
		}
		return candidate;
	}

	/**
	 * 返回写入与建表实际使用的表名。
	 *
	 * @return 经校验并去除首尾空白的表名，非空；构造入参为空或空白时退化为
	 *         {@code audit_log}
	 */
	public String getTableName() {
		return tableName;
	}
}
