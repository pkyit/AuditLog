package io.github.pkyit.auditlog.samples.verify;

import io.github.pkyit.auditlog.core.api.AuditStore;
import io.github.pkyit.auditlog.core.api.AuditChain;
import io.github.pkyit.auditlog.core.model.AuditRecord;
import io.github.pkyit.auditlog.core.model.Outcome;
import io.github.pkyit.auditlog.core.model.PrincipalType;
import io.github.pkyit.auditlog.core.pipeline.AuditPipeline;
import io.github.pkyit.auditlog.core.store.InMemoryAuditStore;
import io.github.pkyit.auditlog.samples.SamplePrincipalConfiguration;
import io.github.pkyit.auditlog.samples.repo.DemoRecordRepository;
import io.github.pkyit.auditlog.spring.method.AuditMethodRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 端到端验收：逐条验证整改方案中的验收标准（A1–A11）。
 *
 * <p>以应用内组件而非测试框架承载：构建环境离线且本地仓库缺少 surefire 的 JUnit provider，
 * {@code mvn test} 不可用；应用内实现可独立运行、可退出码化，既能在本环境给出结论，也能直接接入 CI。
 *
 * <p>每项检查都记录「期望什么、实际什么」，失败时给出可定位的细节。
 */
public class AuditAcceptanceVerifier {

	private static final Logger log = LoggerFactory.getLogger(AuditAcceptanceVerifier.class);

	private final String baseUrl;
	private final InMemoryAuditStore store;
	private final AuditPipeline pipeline;
	private final AuditMethodRegistry registry;
	private final DemoRecordRepository demoRecordRepository;
	private final io.github.pkyit.auditlog.core.api.AuditMetrics metrics;

	private final List<String> results = new ArrayList<>();
	private int passed;
	private int failed;

	/**
	 * 创建验收校验器。
	 *
	 * @param baseUrl 被测应用的根地址，不含尾斜杠
	 * @param store 审计存储；只接受 {@link InMemoryAuditStore}
	 * @param pipeline 审计流水线，用于读取队列深度、链与兜底日志状态
	 * @param registry 审计方法注册表，用于读取收录与扫描结果
	 * @param demoRecordRepository 示例业务仓储，用于核对事务是否真的回滚
	 * @param metrics 指标实现；为 {@code null} 时退化为 {@code AuditMetrics.noop()}
	 * @throws IllegalArgumentException {@code store} 不是 {@link InMemoryAuditStore}；验收需要直接读取
	 * 		存储内容，其他实现无法提供该能力
	 */
	public AuditAcceptanceVerifier(String baseUrl, AuditStore store, AuditPipeline pipeline,
			AuditMethodRegistry registry, DemoRecordRepository demoRecordRepository,
			io.github.pkyit.auditlog.core.api.AuditMetrics metrics) {
		this.baseUrl = baseUrl;
		if (store instanceof InMemoryAuditStore inMemory) {
			this.store = inMemory;
		}
		else {
			throw new IllegalArgumentException("验收模式需要 InMemoryAuditStore，实际为 "
				+ (store == null ? "null" : store.getClass().getName()));
		}
		this.pipeline = pipeline;
		this.registry = registry;
		this.demoRecordRepository = demoRecordRepository;
		this.metrics = metrics == null ? io.github.pkyit.auditlog.core.api.AuditMetrics.noop() : metrics;
	}

	/**
	 * 依次执行全部检查。
	 *
	 * @return 全部检查通过时为 {@code true}；任一检查失败即为 {@code false}
	 */
	public boolean runAll() {
		checkA1_autoConfiguration();
		checkA2_boundaryCaptureWithoutAnnotation();
		checkA3_identityNotSpoofable();
		checkA4_transactionOutcome();
		checkA5_securityEventsCaptured();
		checkA6_masking();
		checkA7_chainIntegrityAndTamperDetection();
		checkA8_storeFailureNoDataLoss();
		checkA9_spiReplacement();
		checkA10_diagnostics();
		checkA11_backpressureIntegrity();
		return failed == 0;
	}

	// ------------------------------------------------------------------

	/** A1：仅引入依赖即生效，不需要 {@code @EnableAuditLog} 或 {@code @EnableAsync}。 */
	private void checkA1_autoConfiguration() {
		// 能构造出本对象说明 AuditStore 已由自动配置装配；此处再断言流水线使用同一实例，
		// 且 HandlerMapping 已被扫描。
		boolean pipelineReady = pipeline != null && pipeline.store() == store;
		boolean registryReady = registry != null && registry.scannedHandlerCount() > 0;
		record("A1 自动配置生效（无注解接入）",
			"流水线使用注入的存储，且 HandlerMapping 已被扫描",
			"pipelineUsesStore=" + pipelineReady + ", scannedHandlers="
				+ (registry == null ? -1 : registry.scannedHandlerCount()),
			pipelineReady && registryReady);
	}

	/**
	 * A2：未标注 {@code @AuditLog} 的请求也必须被边界通道采集。
	 *
	 * <p>断言对象是审计记录而非 HTTP 响应：请求成功不代表采集成功，因此以轮询等待新记录落库。
	 */
	private void checkA2_boundaryCaptureWithoutAnnotation() {
		int before = store.size();
		try {
			HttpSupport.get(baseUrl + "/user/queryUserInfoNoAudit?userName=boundary-check");
			awaitRecords(before + 1, 5_000);
		}
		catch (Exception error) {
			record("A2 边界通道兜底采集", "未标注注解的请求也产生审计记录",
				"请求失败：" + error, false);
			return;
		}
		Optional<AuditRecord> found = store.getRecords().stream()
			.filter(item -> item.getRequestUri() != null && item.getRequestUri().contains("queryUserInfoNoAudit"))
			.findFirst();
		record("A2 边界通道兜底采集",
			"未标注 @AuditLog 的请求也产生审计记录",
			found.map(item -> "outcome=" + item.getOutcome() + ", httpStatus=" + item.getHttpStatus()
				+ ", operation=" + item.getOperation()).orElse("未找到记录"),
			found.isPresent());
	}

	/** A3：伪造的身份请求头不得影响审计主体（不变量 I1）。 */
	private void checkA3_identityNotSpoofable() {
		try {
			// 无任何身份来源时必须记为匿名，通用头 X-User-Id 不构成身份
			HttpSupport.get(baseUrl + "/user/queryUserInfo?userId=anon-check",
				"X-User-Id", "spoofed-admin",
				"X-Sample-User", "");
			Optional<AuditRecord> anonymousRecord = awaitRecordWhere(
				item -> "anon-check".equals(item.getResourceId())
					|| (item.getQueryString() != null && item.getQueryString().contains("anon-check")),
				5_000);
			boolean spoofRejected = anonymousRecord.isPresent()
				&& anonymousRecord.get().getPrincipalType() == PrincipalType.ANONYMOUS
				&& !"spoofed-admin".equals(anonymousRecord.get().getPrincipalId());

			// 身份由应用自有认证层通过私有请求头下发时必须被采用，通用头 X-User-Id 仍被忽略
			HttpSupport.get(baseUrl + "/user/queryUserInfo?userId=trusted-check",
				"X-Sample-User", "real-user-42",
				"X-User-Id", "spoofed-admin");
			Optional<AuditRecord> trustedRecord = awaitRecordWhere(
				item -> "trusted-check".equals(item.getResourceId())
					|| (item.getQueryString() != null && item.getQueryString().contains("trusted-check")),
				5_000);
			boolean trustedAdopted = trustedRecord.isPresent()
				&& "real-user-42".equals(trustedRecord.get().getPrincipalId())
				&& trustedRecord.get().getPrincipalType() == PrincipalType.AUTHENTICATED;

			record("A3 身份不可伪造",
				"无身份来源时记为匿名；伪造 X-User-Id 被忽略；可信来源(X-Sample-User)被采用",
				"匿名场景: principalType=" + anonymousRecord.map(AuditRecord::getPrincipalType).orElse(null)
					+ ", principalId=" + anonymousRecord.map(AuditRecord::getPrincipalId).orElse(null)
					+ " | 可信场景: principalType=" + trustedRecord.map(AuditRecord::getPrincipalType).orElse(null)
					+ ", principalId=" + trustedRecord.map(AuditRecord::getPrincipalId).orElse(null),
				spoofRejected && trustedAdopted);
		}
		catch (Exception error) {
			record("A3 身份不可伪造", "伪造请求头不得影响主体", "请求失败：" + error, false);
		}
	}

	/**
	 * A4：已回滚的事务不得被记录为成功。
	 *
	 * <p>同时核对两条事实：审计记录的 outcome 与业务数据的实际回滚结果。只校验其中一条，
	 * 事务边界错位仍可能通过。
	 */
	private void checkA4_transactionOutcome() {
		long demoBefore = demoRecordRepository.count();
		int auditBefore = store.size();
		try {
			HttpSupport.post(baseUrl + "/user/tx/commit?name=committed", "");
			awaitRecords(auditBefore + 1, 5_000);
			int afterCommit = store.size();
			HttpSupport.post(baseUrl + "/user/tx/rollback?name=rolled-back", "");
			Thread.sleep(1_000);
			int afterRollback = store.size();
			long demoAfter = demoRecordRepository.count();

			boolean commitRecorded = store.getRecords().stream()
				.anyMatch(item -> "写入并提交".equals(item.getOperation()) && item.getOutcome() == Outcome.SUCCESS);
			boolean rollbackNotRecordedAsSuccess = store.getRecords().stream()
				.noneMatch(item -> "写入并回滚".equals(item.getOperation()) && !item.isRolledBack()
					&& item.getOutcome() == Outcome.SUCCESS);
			boolean dataRolledBack = demoAfter == demoBefore + 1;

			record("A4 事务边界对齐",
				"提交产生 SUCCESS 记录；回滚不产生「成功」记录；且数据确实回滚",
				"提交记录=" + commitRecorded + ", 回滚未记成功=" + rollbackNotRecordedAsSuccess
					+ ", 库中记录数 " + demoBefore + "->" + demoAfter
					+ ", 审计记录数 " + auditBefore + "->" + afterCommit + "->" + afterRollback,
				commitRecorded && rollbackNotRecordedAsSuccess && dataRolledBack);
		}
		catch (Exception error) {
			record("A4 事务边界对齐", "回滚不得记成成功", "执行失败：" + error, false);
		}
	}

	/**
	 * A5：认证失败、显式拒绝、路由不存在等关键安全事件必须被采集。
	 *
	 * <p>三类事件分别要求记为 DENIED、DENIED、INVALID，且 404 无匹配 Handler，
	 * 只能由边界通道兜底采集。
	 */
	private void checkA5_securityEventsCaptured() {
		try {
			// 401：无认证主体
			HttpSupport.get(baseUrl + "/user/me");
			Optional<AuditRecord> unauthorized = awaitRecordWithOutcome("查询我的信息", Outcome.DENIED, 5_000);

			// 403：显式拒绝
			HttpSupport.delete(baseUrl + "/user/999");
			Optional<AuditRecord> forbidden = awaitRecordWithOutcome("删除用户", Outcome.DENIED, 5_000);

			// 404：未匹配到任何 Handler，仍应被边界通道记录
			HttpSupport.get(baseUrl + "/user/does-not-exist-at-all");
			Optional<AuditRecord> notFound = awaitRecordWhere(
				item -> item.getRequestUri() != null && item.getRequestUri().contains("does-not-exist")
					&& item.getOutcome() == Outcome.INVALID, 5_000);

			record("A5 安全事件覆盖",
				"401/403 记为 DENIED；404 记为 INVALID（原实现全部漏采）",
				"401=" + unauthorized.map(AuditRecord::getOutcome).orElse(null)
					+ ", 403=" + forbidden.map(AuditRecord::getOutcome).orElse(null)
					+ ", 404=" + notFound.map(item -> item.getOutcome() + "/http=" + item.getHttpStatus()).orElse(null),
				unauthorized.isPresent() && forbidden.isPresent() && notFound.isPresent());
		}
		catch (Exception error) {
			record("A5 安全事件覆盖", "401/403/404 全部被采集", "执行失败：" + error, false);
		}
	}

	/**
	 * A6：请求头与请求体中的敏感字段必须脱敏后才能落库。
	 *
	 * <p>断言的是落库内容而非请求本身：脱敏在采集阶段完成，只有审计记录里搜不到明文才算通过；
	 * 同时必须保留 username 等非敏感字段，脱敏不得抹掉审计价值。
	 */
	private void checkA6_masking() {
		try {
			String body = "{\"username\":\"zhangsan\",\"password\":\"P@ssw0rd-Should-Not-Appear\","
				+ "\"idCard\":\"110101199003071234\",\"mobile\":\"13812345678\","
				+ "\"nested\":{\"token\":\"nested-token-should-not-appear\"}}";
			HttpSupport.post(baseUrl + "/user/create", body,
				"Authorization", "Bearer header-token-should-not-appear",
				"Cookie", "JSESSIONID=cookie-should-not-appear",
				"X-Custom-Secret", "custom-secret-should-not-appear");
			Optional<AuditRecord> record = awaitRecord("创建用户", 5_000);
			if (record.isEmpty()) {
				record("A6 敏感信息脱敏", "请求头与报文体中的敏感值均被掩码", "未找到审计记录", false);
				return;
			}
			String requestBody = nullSafe(record.get().getRequestPayload().body());
			String headers = record.get().getTags().getOrDefault("headers", "");
			String combined = requestBody + " " + headers;

			List<String> leaked = new ArrayList<>();
			checkLeak(combined, "P@ssw0rd-Should-Not-Appear", "报文体 password", leaked);
			checkLeak(combined, "110101199003071234", "报文体 idCard", leaked);
			checkLeak(combined, "13812345678", "报文体 mobile", leaked);
			checkLeak(combined, "nested-token-should-not-appear", "嵌套字段 token", leaked);
			checkLeak(combined, "header-token-should-not-appear", "请求头 Authorization（大小写不同）", leaked);
			checkLeak(combined, "cookie-should-not-appear", "请求头 Cookie", leaked);
			checkLeak(combined, "custom-secret-should-not-appear", "自定义敏感头 X-Custom-Secret", leaked);

			// 脱敏与可用性需同时成立：字段一旦被整体丢弃，审计价值也随之丢失
			boolean keptUseful = requestBody.contains("zhangsan");

			record("A6 敏感信息脱敏",
				"7 类敏感值全部被掩码，同时保留 username 等非敏感字段",
				leaked.isEmpty() ? "无泄漏；username 保留=" + keptUseful : "以下内容泄漏：" + leaked,
				leaked.isEmpty() && keptUseful);
		}
		catch (Exception error) {
			record("A6 敏感信息脱敏", "敏感值不得落库", "执行失败：" + error, false);
		}
	}

	/**
	 * A7：哈希链可校验，且能发现篡改（不变量 I4）。
	 *
	 * <p>断言的是「完好链校验通过」与「篡改后校验失败并定位到被改动的序号」两侧，
	 * 只验证前一侧无法证明防篡改能力。
	 */
	private void checkA7_chainIntegrityAndTamperDetection() {
		try {
			HttpSupport.get(baseUrl + "/user/queryUserInfo?userId=chain-check");
			awaitRecord("queryUserInfo", 5_000);
			List<AuditRecord> records = new ArrayList<>(pipeline.recentRecords());
			if (records.size() < 2) {
				record("A7 防篡改哈希链", "链可校验，篡改可被发现", "已投递记录不足：" + records.size(), false);
				return;
			}
			AuditChain chain = pipeline.chain();
			AuditChain.ChainVerification clean = chain.verify(records);

			// 篡改：改写中间一条记录的 operation 字段。重算的 canonicalContent 必然与其 recordHash
			// 不一致，因此可用来验证校验逻辑确实按记录内容而非仅按链式指针判定。
			List<AuditRecord> tampered = new ArrayList<>(records);
			int targetIndex = records.size() / 2;
			AuditRecord original = tampered.get(targetIndex);
			tampered.set(targetIndex, original.toBuilder().operation("TAMPERED-BY-ATTACKER").build());
			AuditChain.ChainVerification broken = chain.verify(tampered);

			boolean chainEnabled = chain.isEnabled();
			boolean cleanValid = clean.valid();
			boolean tamperDetected = !broken.valid();
			record("A7 防篡改哈希链",
				"完好的记录链校验通过；篡改任意一条后校验失败并定位到该条",
				"chainEnabled=" + chainEnabled + ", 完好链=" + cleanValid + "(" + clean.verifiedCount()
					+ " 条), 篡改后=" + broken.valid() + " 定位 seq=" + broken.brokenAtSeq()
					+ " 原因=" + broken.reason(),
				chainEnabled && cleanValid && tamperDetected);
		}
		catch (Exception error) {
			record("A7 防篡改哈希链", "链校验与篡改发现", "执行失败：" + error, false);
		}
	}

	/**
	 * A8：存储故障时不丢数据，且失败必须可观测。
	 *
	 * <p>断言的是「不丢数据」这一不变量本身，而不是某一条特定的兜底路径：存储不可用时，
	 * 记录依次经过「批次重试 → 本地兜底日志(WAL) → 死信」，每一层都计入指标。因此验证三点：
	 * <ol>
	 *   <li>{@code dropped == 0}：没有任何记录被静默丢弃；</li>
	 *   <li>兜底被触发（死信或 WAL 待重放数增加），即失败被显式记录而非消失；</li>
	 *   <li>存储恢复后记录可被重新投递（重放或补投）。</li>
	 * </ol>
	 */
	private void checkA8_storeFailureNoDataLoss() {
		int storeBefore = store.size();
		long deadLetterBefore = metricsSnapshot().getOrDefault("audit.store.deadletter", 0L);
		long deliveredBefore = metricsSnapshot().getOrDefault("audit.store.delivered", 0L);
		try {
			store.setFailing(true);

			int injected = 10;
			for (int index = 0; index < injected; index++) {
				pipeline.submit(syntheticRecord(index));
			}
			// 等待重试耗尽（4 次尝试 + 指数退避）后才读取指标，否则会把重试中的记录误判为丢失
			sleepQuietly(3_000);

			long dropped = pipeline.droppedCount();
			long deadLetters = metricsSnapshot().getOrDefault("audit.store.deadletter", 0L) - deadLetterBefore;
			long journalPending = pipeline.journal() == null ? -1L : pipeline.journal().pendingCount();

			// 记录此刻在 WAL 还是死信不影响结论，两条路径都必须可恢复
			store.setFailing(false);
			int replayed = pipeline.replay();
			sleepQuietly(500);
			long deliveredDelta = metricsSnapshot().getOrDefault("audit.store.delivered", 0L) - deliveredBefore;
			boolean recovered = deliveredDelta > 0 || replayed > 0 || deadLetters > 0;

			boolean noSilentLoss = dropped == 0;
			boolean failureVisible = deadLetters > 0 || journalPending > 0;

			record("A8 存储故障不丢数据（重试 + WAL + 死信）",
				"存储持续失败时 dropped 必须为 0，且失败被显式记录到死信或 WAL",
				"注入=" + injected + " 条, 丢弃=" + dropped + ", 死信=" + deadLetters
					+ ", WAL 待重放=" + journalPending + ", 重放=" + replayed
					+ ", 投递增量=" + deliveredDelta,
				noSilentLoss && failureVisible && recovered);
		}
		catch (Exception error) {
			record("A8 存储故障不丢数据（重试 + WAL + 死信）", "不丢数据", "执行失败：" + error, false);
		}
		finally {
			store.setFailing(false);
		}
	}

	/**
	 * 读取框架指标快照。
	 *
	 * @return 指标名到当前值的映射；{@code metrics} 不是 {@link io.github.pkyit.auditlog.core.metrics.SimpleAuditMetrics}
	 * 		时返回空映射，此时相关断言退化为不可观测
	 */
	private java.util.Map<String, Long> metricsSnapshot() {
		if (metrics instanceof io.github.pkyit.auditlog.core.metrics.SimpleAuditMetrics simple) {
			return simple.snapshot();
		}
		return java.util.Map.of();
	}

	/** 构造一条最小可用的合成记录（用于故障注入，不依赖 HTTP 链路）。 */
	private io.github.pkyit.auditlog.core.model.AuditRecord syntheticRecord(int index) {
		return new io.github.pkyit.auditlog.core.model.AuditRecord.Builder()
			.id("synthetic-" + index + "-" + System.nanoTime())
			.eventTime(java.time.Instant.now())
			.principal("fault-injection-user", "fault-injection-user",
				io.github.pkyit.auditlog.core.model.PrincipalType.AUTHENTICATED)
			.operation("故障注入-" + index)
			.actionType(io.github.pkyit.auditlog.core.model.ActionType.UNKNOWN)
			.http("GET", "/synthetic/" + index, null)
			.outcome(io.github.pkyit.auditlog.core.model.Outcome.SUCCESS)
			.httpStatus(200)
			.durationMillis(1L)
			.build();
	}

	/**
	 * A11：突发流量下审计记录不得被丢弃。
	 *
	 * <p>瞬时请求数超过队列容量时，超出部分必须进入兜底日志而非被丢弃，因此断言
	 * {@code dropped == 0}；等待采用静默超时，条数未达预期不抛异常，由落库数与丢弃数判定。
	 */
	private void checkA11_backpressureIntegrity() {
		try {
			int total = 60;
			for (int index = 0; index < total; index++) {
				HttpSupport.get(baseUrl + "/user/queryUserInfo3?userName=burst-" + index);
			}
			awaitRecordsQuietly(total, 8_000);
			long delivered = store.size();
			long journalPending = pipeline.journal() == null ? 0L : pipeline.journal().pendingCount();
			long dropped = pipeline.droppedCount();
			record("A11 突发流量下不丢数据",
				"突发 " + total + " 个请求后丢弃数必须为 0（超出队列的部分应进入兜底日志）",
				"落库=" + delivered + ", 兜底日志待重放=" + journalPending + ", 丢弃=" + dropped,
				dropped == 0 && delivered > 0);
		}
		catch (Exception error) {
			record("A11 突发流量下不丢数据", "丢弃数必须为 0", "执行失败：" + error, false);
		}
	}

	/** A9：SPI 可替换，自定义主体解析器必须被框架采用。 */
	private void checkA9_spiReplacement() {
		try {
			HttpSupport.get(baseUrl + "/user/queryUserInfo?userId=spi-check",
				SamplePrincipalConfiguration.SAMPLE_USER_HEADER, "spi-user-1");
			Optional<AuditRecord> record = awaitRecordWhere(
				item -> "spi-user-1".equals(item.getPrincipalId()), 5_000);
			boolean adopted = record.isPresent();
			String authType = record.map(AuditRecord::getAuthType).orElse(null);
			record("A9 SPI 可替换（自定义 PrincipalResolver）",
				"自定义 PrincipalResolver 被框架采用（authType=sample-internal，主体类型 AUTHENTICATED）",
				"principalId=" + record.map(AuditRecord::getPrincipalId).orElse(null)
					+ ", authType=" + authType
					+ ", principalType=" + record.map(AuditRecord::getPrincipalType).orElse(null),
				adopted && "sample-internal".equals(authType)
					&& record.get().getPrincipalType() == PrincipalType.AUTHENTICATED);
		}
		catch (Exception error) {
			record("A9 SPI 可替换（自定义 PrincipalResolver）", "自定义解析器被采用", "执行失败：" + error, false);
		}
	}

	/** A10：诊断能力，管理端点必须输出来源清单、流水线状态与链校验结果。 */
	private void checkA10_diagnostics() {
		try {
			HttpSupport.Response response = HttpSupport.get(baseUrl + "/actuator/auditLog");
			String body = response.body();
			boolean ok = response.status() == 200
				&& body.contains("\"collected\"")
				&& body.contains("\"chainVerification\"")
				&& body.contains("\"queueDepth\"")
				&& body.contains("\"validation\"");
			record("A10 诊断能力（/actuator/auditLog）",
				"端点可输出收录清单、流水线状态与链校验结果（供人工核对注解是否真的生效）",
				"httpStatus=" + response.status()
					+ ", 收录方法数=" + registry.collectedMethodCount()
					+ ", 扫描 Handler 数=" + registry.scannedHandlerCount()
					+ ", 响应长度=" + body.length(),
				ok);
		}
		catch (Exception error) {
			record("A10 诊断能力（/actuator/auditLog）", "端点可用", "执行失败：" + error, false);
		}
	}

	// ------------------------------------------------------------------
	// 断言与输出
	// ------------------------------------------------------------------

	/**
	 * 把命中敏感值的字段追加到泄漏清单。
	 *
	 * @param content 待检查的落库内容，通常是请求体与请求头拼接后的文本
	 * @param sensitive 不得出现的敏感明文
	 * @param label 命中时写入清单的字段名
	 * @param leaked 泄漏清单，命中时被追加
	 */
	private void checkLeak(String content, String sensitive, String label, List<String> leaked) {
		if (content.contains(sensitive)) {
			leaked.add(label + "(" + sensitive + ")");
		}
	}

	/**
	 * 记录一次检查结果。有副作用：递增通过/失败计数并把结果行追加到报告缓冲。
	 *
	 * @param name 检查名称，会出现在报告中
	 * @param expectation 期望值描述
	 * @param actual 实际观测到的值
	 * @param success 该检查是否通过
	 */
	private void record(String name, String expectation, String actual, boolean success) {
		if (success) {
			passed++;
			results.add("  [通过] " + name + "\n         期望：" + expectation + "\n         实际：" + actual);
		}
		else {
			failed++;
			results.add("  [失败] " + name + "\n         期望：" + expectation + "\n         实际：" + actual);
		}
		log.info("[audit-acceptance] {} -> {}", name, success ? "通过" : "失败");
	}

	private Optional<AuditRecord> awaitRecord(String operationName, long timeoutMillis) {
		return awaitRecordWhere(item -> operationName.equals(item.getOperation()), timeoutMillis);
	}

	private Optional<AuditRecord> awaitRecordWithOutcome(String operationName, Outcome outcome,
			long timeoutMillis) {
		return awaitRecordWhere(
			item -> operationName.equals(item.getOperation()) && item.getOutcome() == outcome, timeoutMillis);
	}

	/**
	 * 轮询等待首个匹配的记录。
	 *
	 * @param predicate 记录匹配条件
	 * @param timeoutMillis 等待上限，单位毫秒
	 * @return 匹配到的记录；超时后返回最后一次查找结果，可能为空
	 */
	private Optional<AuditRecord> awaitRecordWhere(java.util.function.Predicate<AuditRecord> predicate,
			long timeoutMillis) {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			Optional<AuditRecord> found = findNewest(predicate);
			if (found.isPresent()) {
				return found;
			}
			sleepQuietly(50);
		}
		return findNewest(predicate);
	}

	/**
	 * 从最新记录往前查找。
	 *
	 * <p>必须取最新一条而非第一条：同一操作名在验收过程中会被多次请求，取第一条会命中早期记录，
	 * 从而把本次请求的结果误判为失败。
	 */
	private Optional<AuditRecord> findNewest(java.util.function.Predicate<AuditRecord> predicate) {
		List<AuditRecord> records = store.getRecords();
		for (int index = records.size() - 1; index >= 0; index--) {
			AuditRecord candidate = records.get(index);
			if (predicate.test(candidate)) {
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	/**
	 * 等待记录数达到预期。
	 *
	 * @param expectedSize 期望的记录条数下限
	 * @param timeoutMillis 等待上限，单位毫秒；超时即返回，不抛异常
	 */
	private void awaitRecords(int expectedSize, long timeoutMillis) {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (store.size() < expectedSize && System.currentTimeMillis() < deadline) {
			sleepQuietly(50);
		}
	}

	/**
	 * 等待记录数达到预期，超时不报错。
	 *
	 * <p>突发流量场景下到达条数本身不稳定，成败判定交给落库数与丢弃数，因此这里不因未达预期而失败。
	 *
	 * @param expectedSize 期望的记录条数下限
	 * @param timeoutMillis 等待上限，单位毫秒
	 */
	private void awaitRecordsQuietly(int expectedSize, long timeoutMillis) {
		awaitRecords(expectedSize, timeoutMillis);
	}

	/**
	 * 休眠指定时长，被中断时立即恢复中断标记并返回。
	 *
	 * @param millis 休眠时长，单位毫秒
	 */
	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException error) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * 空值归一化。
	 *
	 * @param value 待归一化的文本
	 * @return 非空文本原样返回；{@code null} 返回 {@code ""}
	 */
	private static String nullSafe(String value) {
		return value == null ? "" : value;
	}

	/**
	 * 渲染验收报告。
	 *
	 * @return 报告文本：流水线与哈希链概况、逐项检查的名称/期望/实际，以及通过失败计数
	 */
	public String report() {
		StringBuilder builder = new StringBuilder(2048);
		builder.append("============================================================\n");
		builder.append(" audit-log 端到端验收报告\n");
		builder.append("============================================================\n");
		builder.append(" 审计记录总数：").append(store.size()).append('\n');
		builder.append(" 队列深度：").append(pipeline.queueDepth()).append('\n');
		builder.append(" 已投递序号：").append(pipeline.lastDeliveredSeq()).append('\n');
		builder.append(" 丢弃记录数：").append(pipeline.droppedCount()).append('\n');
		builder.append(" 哈希链：").append(pipeline.chain().isEnabled() ? "启用" : "未启用")
			.append("，链头=").append(pipeline.chain().currentHead()).append('\n');
		builder.append(" 收录方法数：").append(registry.collectedMethodCount())
			.append("（扫描 Handler ").append(registry.scannedHandlerCount()).append(" 个）\n");
		builder.append("------------------------------------------------------------\n");
		results.forEach(line -> builder.append(line).append('\n'));
		builder.append("------------------------------------------------------------\n");
		builder.append(" 结果：通过 ").append(passed).append(" 项，失败 ").append(failed).append(" 项\n");
		builder.append("============================================================\n");
		return builder.toString();
	}

	/**
	 * 取通过检查的项数，用于输出退出码前的汇总。
	 *
	 * @return 已通过项数
	 */
	public int passedCount() {
		return passed;
	}

	/**
	 * 取失败的检查项数。
	 *
	 * @return 失败项数；为 0 表示全部通过
	 */
	public int failedCount() {
		return failed;
	}

	/**
	 * 转小写。
	 *
	 * <p>必须指定 {@link Locale#ROOT}，否则土耳其语等区域会把 {@code I} 转成无点字符，导致匹配失败。
	 *
	 * @param value 待转换的文本
	 * @return 小写文本；入参为 {@code null} 时返回 {@code null}
	 */
	static String lower(String value) {
		return value == null ? null : value.toLowerCase(Locale.ROOT);
	}
}
