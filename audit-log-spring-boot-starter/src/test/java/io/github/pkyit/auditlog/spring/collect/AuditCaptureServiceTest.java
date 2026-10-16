package io.github.pkyit.auditlog.spring.collect;

import io.github.pkyit.auditlog.core.api.AuditFailureHandler;
import io.github.pkyit.auditlog.core.api.AuditMetricNames;
import io.github.pkyit.auditlog.core.api.ClientIpResolver;
import io.github.pkyit.auditlog.core.api.PrincipalInfo;
import io.github.pkyit.auditlog.core.api.PrincipalResolver;
import io.github.pkyit.auditlog.core.api.TenantResolver;
import io.github.pkyit.auditlog.core.api.TraceIdResolver;
import io.github.pkyit.auditlog.core.chain.Sha256AuditChain;
import io.github.pkyit.auditlog.core.mask.RuleBasedAuditMasker;
import io.github.pkyit.auditlog.core.metrics.SimpleAuditMetrics;
import io.github.pkyit.auditlog.core.model.ActionType;
import io.github.pkyit.auditlog.core.model.AuditRecord;
import io.github.pkyit.auditlog.core.model.Outcome;
import io.github.pkyit.auditlog.core.pipeline.AuditPipeline;
import io.github.pkyit.auditlog.core.pipeline.BackpressureStrategy;
import io.github.pkyit.auditlog.core.serialize.JacksonAuditSerializer;
import io.github.pkyit.auditlog.core.serialize.JsonLinesAuditCodec;
import io.github.pkyit.auditlog.core.store.InMemoryAuditStore;
import io.github.pkyit.auditlog.core.wal.FileAuditJournal;
import io.github.pkyit.auditlog.spring.annotation.AuditLog;
import io.github.pkyit.auditlog.spring.method.AuditMethodMetadata;
import io.github.pkyit.auditlog.spring.method.AuditMethodRegistry;
import io.github.pkyit.auditlog.spring.properties.AuditLogProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.method.HandlerMethod;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采集层行为测试：审计链路的异常不得传播到业务线程。
 *
 * <p>采集过程中抛出的异常（含 {@code toString()} 与事件发布）不得覆盖原始业务异常，
 * 否则业务故障会被伪装成审计故障，原始状态码与异常信息一并丢失。
 */
class AuditCaptureServiceTest {

	/** 测试用控制器：用于构造真实的 {@link HandlerMethod}。 */
	static class DemoController {

		@AuditLog(name = "创建订单", actionType = ActionType.MUTATE, resourceType = "order",
			resourceIdParam = "orderId", sensitive = true, tags = {"biz=order"})
		@PostMapping("/api/order")
		public String create(String orderId) {
			return orderId;
		}

		/** 未标注审计注解的方法。 */
		@PostMapping("/api/plain")
		public String plain(String value) {
			return value;
		}
	}

	private final SimpleAuditMetrics metrics = new SimpleAuditMetrics();
	private AuditPipeline pipeline;

	@AfterEach
	void tearDown() {
		if (pipeline != null) {
			pipeline.stop();
		}
	}

	private AuditCaptureService service(List<PrincipalResolver> resolvers, Path journalDir,
			AuditMethodRegistry registry) {
		AuditLogProperties properties = new AuditLogProperties();
		properties.setServiceName("test-service");
		properties.getCollector().getMethod().setEnabled(true);

		InMemoryAuditStore store = new InMemoryAuditStore(metrics);
		FileAuditJournal journal = new FileAuditJournal(journalDir, new JsonLinesAuditCodec(), metrics,
			1024L * 1024, 3, 128, 256);
		try {
			journal.open();
		}
		catch (Exception error) {
			throw new IllegalStateException("无法打开测试用 WAL", error);
		}
		pipeline = new AuditPipeline(new AuditPipeline.Config(128, 10, 50L, 1, BackpressureStrategy.SPILL,
			200L, 0, 50L, false, 128), store, new Sha256AuditChain(), metrics,
			AuditFailureHandler.logging(), journal);
		// 必须启动流水线：否则记录只停留在队列中，不会进入存储与 recentRecords，
		// 断言将观测不到任何记录。
		pipeline.start();

		return new AuditCaptureService(properties, registry, resolvers,
			(ClientIpResolver) context -> Optional.ofNullable(context.getRemoteAddress()),
			(TenantResolver) context -> Optional.ofNullable(context.getHeader("X-Tenant-Id")),
			(TraceIdResolver) context -> context.getHeader("X-Trace-Id"),
			new RuleBasedAuditMasker(JacksonAuditSerializer.defaultMapper(), metrics, List.of()),
			new JacksonAuditSerializer(), pipeline, metrics, AuditFailureHandler.logging());
	}

	private MockHttpServletRequest request() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/order");
		request.setRemoteAddr("10.1.2.3");
		request.addHeader("X-Trace-Id", "trace-abc");
		request.addHeader("X-Tenant-Id", "tenant-1");
		request.addParameter("orderId", "1001");
		return request;
	}

	private HandlerMethod handlerOf(String methodName) {
		try {
			java.lang.reflect.Method method = DemoController.class.getDeclaredMethod(methodName, String.class);
			return new HandlerMethod(new DemoController(), method);
		}
		catch (NoSuchMethodException error) {
			throw new IllegalStateException(error);
		}
	}

	private AuditMethodRegistry registryWithMetadata() {
		HandlerMethod handler = handlerOf("create");
		// 元数据必须由与真实扫描相同的工厂方法构造（它负责派生 sensitive 标签并解析 tags）；
		// 手工拼装只能验证测试自身的构造逻辑，覆盖不到框架的元数据派生行为。
		AuditLog annotation = org.springframework.core.annotation.AnnotatedElementUtils
			.findMergedAnnotation(handler.getMethod(), AuditLog.class);
		assertNotNull(annotation, "测试用方法应带 @AuditLog");
		// keyOf / single 属于 Servlet 侧的扫描实现（依赖 RequestMappingHandlerMapping
		// 这类 servlet 类型），因此这里使用 ServletMethodRegistry：注册表契约
		// （AuditMethodRegistry）与环境无关，具体扫描实现按环境分派。
		String key = io.github.pkyit.auditlog.spring.method.ServletMethodRegistry.keyOf(handler);
		AuditMethodMetadata metadata = AuditMethodMetadata.of(key, handler, annotation);
		return io.github.pkyit.auditlog.spring.method.ServletMethodRegistry.single(key, metadata);
	}

	@Test
	@DisplayName("主体解析器抛异常时，采集不得抛出，且降级为匿名")
	void survivesBrokenPrincipalResolver(@TempDir Path tempDir) {
		PrincipalResolver broken = context -> {
			throw new IllegalStateException("模拟认证框架故障");
		};
		AuditCaptureService service = service(List.of(broken), tempDir.resolve("j"), AuditMethodRegistry.empty());

		MockHttpServletRequest request = request();
		AuditRecordContext context = assertDoesNotThrow(() -> service.begin(view(request)),
			"主体解析器故障不得冒泡到业务线程");
		assertDoesNotThrow(() -> service.finish(context, view(request),
			response(new MockHttpServletResponse()), null));

		assertTrue(metrics.counter(AuditMetricNames.COLLECT_ERROR) > 0,
			"解析失败必须被计数（否则就是静默降级）");
		AuditRecord record = awaitFirstRecord();
		assertNotNull(record, "采集失败也必须留下记录");
		assertEquals(PrincipalInfo.anonymous().type(), record.getPrincipalType(),
			"解析失败时主体应为匿名，绝不能伪装成可信身份");
	}

	@Test
	@DisplayName("链路 ID 解析失败时回退为内部 ID，保证记录仍可被追踪")
	void fallsBackToLocalTraceId(@TempDir Path tempDir) {
		TraceIdResolver broken = context -> {
			throw new IllegalStateException("模拟链路组件故障");
		};
		AuditCaptureService service = service(java.util.List.<PrincipalResolver>of(context -> Optional.of(PrincipalInfo.anonymous())), tempDir.resolve("j"),
			AuditMethodRegistry.empty());
		// 故障的 traceIdResolver 无法经 service(...) 工厂注入，只能在此单独构造实例。
		AuditCaptureService withBrokenTrace = new AuditCaptureService(
			serviceProperties(), AuditMethodRegistry.empty(), java.util.List.<PrincipalResolver>of(context -> Optional.of(PrincipalInfo.anonymous())),
			(ClientIpResolver) context -> Optional.ofNullable(context.getRemoteAddress()),
			(TenantResolver) context -> Optional.empty(), broken,
			new RuleBasedAuditMasker(JacksonAuditSerializer.defaultMapper(), metrics, List.of()),
			new JacksonAuditSerializer(), pipeline, metrics, AuditFailureHandler.logging());

		MockHttpServletRequest request = request();
		AuditRecordContext context = withBrokenTrace.begin(view(request));
		withBrokenTrace.finish(context, view(request), response(new MockHttpServletResponse()), null);

		AuditRecord record = awaitFirstRecord();
		assertNotNull(record);
		assertNotNull(record.getTraceId(), "链路 ID 缺失时必须回退生成，而不是留空导致无法关联");
		assertTrue(record.getTraceId().startsWith("local-"), "回退 ID 应可识别，实际=" + record.getTraceId());
	}

	/** 把 Servlet 请求包装成环境无关的请求视图（采集编排只认视图）。 */
	private static AuditRequestView view(MockHttpServletRequest request) {
		return new io.github.pkyit.auditlog.spring.servlet.ServletAuditRequestView(request);
	}

	/** 把 Servlet 响应包装成环境无关的响应视图。 */
	private static AuditResponseView response(MockHttpServletResponse response) {
		return new io.github.pkyit.auditlog.spring.servlet.ServletAuditResponseView(response);
	}

	private AuditLogProperties serviceProperties() {
		AuditLogProperties properties = new AuditLogProperties();
		properties.setServiceName("test-service");
		return properties;
	}

	@Test
	@DisplayName("结果判定：按真实状态码区分成功/拒绝/无效/失败")
	void decidesOutcomeFromRealStatus(@TempDir Path tempDir) {
		AuditCaptureService service = service(java.util.List.<PrincipalResolver>of(context -> Optional.of(PrincipalInfo.anonymous())), tempDir.resolve("j"),
			AuditMethodRegistry.empty());

		recordWithStatus(service, 403);
		recordWithStatus(service, 400);
		recordWithStatus(service, 500);
		recordWithStatus(service, 200);

		waitForRecords(4);
		List<Outcome> outcomes = pipeline.recentRecords().stream().map(AuditRecord::getOutcome).toList();
		assertTrue(outcomes.contains(Outcome.DENIED), "403 应判定为 DENIED，实际=" + outcomes);
		assertTrue(outcomes.contains(Outcome.INVALID), "400 应判定为 INVALID，实际=" + outcomes);
		assertTrue(outcomes.contains(Outcome.FAILURE), "500 应判定为 FAILURE，实际=" + outcomes);
		assertTrue(outcomes.contains(Outcome.SUCCESS), "200 应判定为 SUCCESS，实际=" + outcomes);
	}

	private void recordWithStatus(AuditCaptureService service, int status) {
		MockHttpServletRequest request = request();
		AuditRecordContext context = service.begin(view(request));
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setStatus(status);
		service.finish(context, view(request), response(response), null);
	}

	@Test
	@DisplayName("语义通道：命中注解时写入操作名、动作类型与资源；未命中时不报错")
	void appliesSemanticMetadata(@TempDir Path tempDir) {
		AuditCaptureService service = service(java.util.List.<PrincipalResolver>of(context -> Optional.of(PrincipalInfo.anonymous())), tempDir.resolve("j"),
			registryWithMetadata());

		MockHttpServletRequest request = request();
		AuditRecordContext context = service.begin(view(request));
		assertDoesNotThrow(() -> service.applySemantic(context, handlerOf("create")));
		service.finishSemantic(context, view(request), new Object[] {"1001"}, 5L);
		service.finish(context, view(request), response(new MockHttpServletResponse()), null);

		AuditRecord record = awaitFirstRecord();
		assertNotNull(record);
		assertEquals("创建订单", record.getOperation(), "应写入注解声明的操作名");
		assertEquals(ActionType.MUTATE, record.getActionType(), "应写入动作类型");
		assertEquals("order", record.getResourceType());
		assertEquals("1001", record.getResourceId(), "应按 resourceIdParam 从请求参数解析资源 ID");
		assertEquals("true", record.getTags().get("sensitive"), "sensitive 标记应写入标签");
		assertEquals("order", record.getTags().get("biz"), "自定义标签应被保留");
	}

	@Test
	@DisplayName("语义通道：未标注注解的 Handler 不报错，仅边界采集")
	void ignoresUnannotatedHandler(@TempDir Path tempDir) {
		AuditCaptureService service = service(java.util.List.<PrincipalResolver>of(context -> Optional.of(PrincipalInfo.anonymous())), tempDir.resolve("j"),
			registryWithMetadata());

		MockHttpServletRequest request = request();
		AuditRecordContext context = service.begin(view(request));
		// plain 方法不在注册表中：必须安全返回 null，而不是抛异常
		assertDoesNotThrow(() -> assertNull(service.applySemantic(context, handlerOf("plain")),
			"未标注注解的 Handler 应返回 null 由边界通道兜底"));
		assertDoesNotThrow(() -> service.finishSemantic(context, view(request), new Object[] {"x"}, 1L),
			"无元数据时 finishSemantic 应安全返回");
	}

	@Test
	@DisplayName("请求头与查询串在采集阶段完成脱敏（入队即已脱敏）")
	void masksHeadersAtCaptureTime(@TempDir Path tempDir) {
		AuditCaptureService service = service(java.util.List.<PrincipalResolver>of(context -> Optional.of(PrincipalInfo.anonymous())), tempDir.resolve("j"),
			registryWithMetadata());

		MockHttpServletRequest request = request();
		request.addHeader("Authorization", "Bearer leaked-token-value");
		request.addHeader("Cookie", "JSESSIONID=leaked-session");
		AuditRecordContext context = service.begin(view(request));
		service.applySemantic(context, handlerOf("create"));
		service.finish(context, view(request), response(new MockHttpServletResponse()), null);

		AuditRecord record = awaitFirstRecord();
		assertNotNull(record);
		String headers = record.getTags().getOrDefault("headers", "");
		assertTrue(headers.contains("Authorization"), "请求头应被采集");
		assertTrue(!headers.contains("leaked-token-value"), "令牌必须在入队前就被掩码，实际=" + headers);
		assertTrue(!headers.contains("leaked-session"), "会话 Cookie 必须在入队前就被掩码");
	}

	@Test
	@DisplayName("停机排空：采集完成后 pipeline 能干净停止")
	void pipelineStopsCleanlyAfterCapture(@TempDir Path tempDir) {
		AuditCaptureService service = service(java.util.List.<PrincipalResolver>of(context -> Optional.of(PrincipalInfo.anonymous())), tempDir.resolve("j"),
			AuditMethodRegistry.empty());
		MockHttpServletRequest request = request();
		service.finish(service.begin(view(request)), view(request),
			response(new MockHttpServletResponse()), null);
		awaitFirstRecord();
		assertDoesNotThrow(() -> pipeline.stop(), "停机不得抛异常");
		assertEquals(0, pipeline.queueDepth(), "停机后队列应为空");
	}

	private AuditRecord awaitFirstRecord() {
		long deadline = System.currentTimeMillis() + 5_000L;
		while (pipeline.recentRecords().isEmpty() && System.currentTimeMillis() < deadline) {
			sleep(20L);
		}
		List<AuditRecord> records = pipeline.recentRecords();
		return records.isEmpty() ? null : records.get(0);
	}

	private void waitForRecords(int expected) {
		long deadline = System.currentTimeMillis() + 5_000L;
		while (pipeline.recentRecords().size() < expected && System.currentTimeMillis() < deadline) {
			sleep(20L);
		}
	}

	private void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException error) {
			Thread.currentThread().interrupt();
		}
	}
}
