package io.github.pkyit.auditlog.spring.collect;

import io.github.pkyit.auditlog.core.api.AuditFailureHandler;
import io.github.pkyit.auditlog.core.api.AuditMasker;
import io.github.pkyit.auditlog.core.api.AuditMetricNames;
import io.github.pkyit.auditlog.core.api.AuditMetrics;
import io.github.pkyit.auditlog.core.api.AuditSerializer;
import io.github.pkyit.auditlog.core.api.CaptureOptions;
import io.github.pkyit.auditlog.core.api.ClientIpResolver;
import io.github.pkyit.auditlog.core.api.PrincipalInfo;
import io.github.pkyit.auditlog.core.api.PrincipalResolver;
import io.github.pkyit.auditlog.core.api.TenantResolver;
import io.github.pkyit.auditlog.core.api.TraceIdResolver;
import io.github.pkyit.auditlog.core.mask.FieldMaskRule;
import io.github.pkyit.auditlog.core.model.ActionType;
import io.github.pkyit.auditlog.core.model.AuditRecord;
import io.github.pkyit.auditlog.core.model.BusinessResult;
import io.github.pkyit.auditlog.core.model.ErrorInfo;
import io.github.pkyit.auditlog.core.model.Outcome;
import io.github.pkyit.auditlog.core.model.Payload;
import io.github.pkyit.auditlog.core.pipeline.AuditPipeline;
import io.github.pkyit.auditlog.spring.identity.AnonymousPrincipalResolver;
import io.github.pkyit.auditlog.spring.identity.DefaultClientIpResolver;
import io.github.pkyit.auditlog.spring.identity.HeaderPrincipalResolver;
import io.github.pkyit.auditlog.spring.identity.SecurityContextPrincipalResolver;
import io.github.pkyit.auditlog.spring.method.AuditMethodMetadata;
import io.github.pkyit.auditlog.spring.method.AuditMethodRegistry;
import io.github.pkyit.auditlog.spring.properties.AuditLogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.web.method.HandlerMethod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 审计采集服务 —— 边界通道与语义通道的汇合点。
 *
 * <p>采用「单记录分阶段填充」模型：
 * <ol>
 *   <li>{@link #begin} 在请求进入时建立上下文（主体、来源、链路、URI），并预先算出
 *       {@link CapturePlan}；</li>
 *   <li>{@link #applySemantic} 由语义通道在 HandlerMethod 调用时补入操作名、动作类型、
 *       资源、耗时等审计语义；</li>
 *   <li>{@link #finish} 在请求结束时读取真实状态码、判定结果，脱敏后发布唯一一条记录。</li>
 * </ol>
 *
 * <p>所有公开方法都不把异常抛给业务代码：审计链路的任何失败都只记录、计数与告警。
 * 记录只在 {@link #finish} 中发布一次，重复调用不会产生第二条记录，
 * 该不变量由 {@link AuditRecordContext#isPublished()} 守卫。
 */
public class AuditCaptureService {

	private static final Logger log = LoggerFactory.getLogger(AuditCaptureService.class);


	private final AuditLogProperties properties;
	private final AuditMethodRegistry methodRegistry;
	private final List<PrincipalResolver> principalResolvers;
	private final ClientIpResolver clientIpResolver;
	private final TenantResolver tenantResolver;
	private final TraceIdResolver traceIdResolver;
	private final AuditMasker masker;
	private final AuditSerializer serializer;
	private final AuditPipeline pipeline;
	private final AuditMetrics metrics;
	private final AuditFailureHandler failureHandler;

	/**
	 * 创建采集服务。
	 *
	 * <p>可选依赖按退化规则处理：{@code principalResolvers} 为 {@code null} 时按空链处理，
	 * 主体解析退化为匿名；{@code metrics} 为 {@code null} 时使用 {@code noop} 实现；
	 * {@code failureHandler} 为 {@code null} 时使用日志实现。
	 *
	 * @param principalResolvers 已按 order 排好序的主体解析器链；由外部（构建器）负责把自定义
	 *                           实现与框架默认实现合并，避免在容器中注册
	 *                           {@code List<PrincipalResolver>} 这种会与其他 Bean 产生
	 *                           歧义的集合 Bean
	 */
	public AuditCaptureService(AuditLogProperties properties,
			AuditMethodRegistry methodRegistry,
			List<PrincipalResolver> principalResolvers,
			ClientIpResolver clientIpResolver,
			TenantResolver tenantResolver,
			TraceIdResolver traceIdResolver,
			AuditMasker masker,
			AuditSerializer serializer,
			AuditPipeline pipeline,
			AuditMetrics metrics,
			AuditFailureHandler failureHandler) {
		this.properties = properties;
		this.methodRegistry = methodRegistry;
		this.principalResolvers = principalResolvers == null ? List.of() : List.copyOf(principalResolvers);
		this.clientIpResolver = clientIpResolver;
		this.tenantResolver = tenantResolver;
		this.traceIdResolver = traceIdResolver;
		this.masker = masker;
		this.serializer = serializer;
		this.pipeline = pipeline;
		this.metrics = metrics == null ? AuditMetrics.noop() : metrics;
		this.failureHandler = failureHandler == null ? AuditFailureHandler.logging() : failureHandler;
	}

	/**
	 * 构建主体解析器链：自定义实现优先，其后是框架默认实现与匿名兜底。
	 *
	 * <p>不通过容器注册「集合 Bean」：{@code List<PrincipalResolver>} 作为 Bean 会在使用者
	 * 提供任意自定义解析器时产生注入歧义，合并职责因此留在本方法内。
	 *
	 * <p>返回的链已按 {@link AnnotationAwareOrderComparator} 排序，并以
	 * {@code AnonymousPrincipalResolver} 结尾，保证任何请求都能解析出主体。
	 *
	 * @param customResolvers 使用者提供的解析器；为 {@code null} 时不贡献任何实现
	 * @param securityResolver Spring Security 解析器；为 {@code null} 或链中已有同类型实现时不追加
	 * @param properties 配置，用于判断是否启用请求头主体解析
	 */
	public static List<PrincipalResolver> buildResolverChain(List<PrincipalResolver> customResolvers,
			PrincipalResolver securityResolver, AuditLogProperties properties) {
		List<PrincipalResolver> resolvers = new ArrayList<>();
		if (customResolvers != null) {
			customResolvers.stream()
				.filter(resolver -> !(resolver instanceof AnonymousPrincipalResolver))
				.forEach(resolvers::add);
		}
		if (securityResolver != null && resolvers.stream()
				.noneMatch(resolver -> resolver instanceof SecurityContextPrincipalResolver)) {
			resolvers.add(securityResolver);
		}
		if (properties.getIdentity().isHeaderPrincipalEnabled()) {
			resolvers.add(new HeaderPrincipalResolver(properties.getIdentity().getPrincipalIdHeader(),
				properties.getIdentity().getPrincipalNameHeader()));
			log.warn("[audit-log] 已开启请求头主体解析（header-principal-enabled=true）："
				+ "审计主体将标记为 TRUSTED_HEADER，其可信度取决于上游网关，请确保应用不可被直连访问");
		}
		resolvers.add(new AnonymousPrincipalResolver());
		resolvers.sort(org.springframework.core.annotation.AnnotationAwareOrderComparator.INSTANCE);
		return resolvers;
	}

	// ------------------------------------------------------------------
	// 阶段一：请求进入
	// ------------------------------------------------------------------

	/**
	 * 建立审计上下文并填充来源信息（不发布）。
	 *
	 * <p>入参是环境无关的请求视图（Servlet / Reactive 各有一份实现），
	 * 因此本方法的审计语义在两种环境下完全一致。
	 */
	public AuditRecordContext begin(AuditRequestView request) {
		AuditRecordContext context = new AuditRecordContext(environmentOf(request));
		AuditRecord.Builder builder = context.builder();

		PrincipalResolver.ResolutionContext resolutionContext = resolutionContextOf(request);
		PrincipalInfo principal = resolvePrincipal(resolutionContext);
		String traceId = resolveTraceId(resolutionContext);
		String tenantId = resolveTenant(resolutionContext);
		String clientIp = resolveClientIp(request);

		String uri = request.uri();
		String query = request.queryString();

		builder.id(AuditRecordContext.nextId());
		builder.eventTime(java.time.Instant.now());
		builder.zoneId(context.zoneId().getId());
		builder.principal(principal.id(), principal.name(), principal.type());
		builder.authType(principal.authType());
		builder.sessionId(sessionIdOf(request));
		builder.traceId(traceId);
		builder.tenantId(tenantId);
		builder.clientIp(clientIp);
		builder.userAgent(truncate(request.header("User-Agent"), 512));
		builder.http(request.method(), uri, properties.getMasking().isCaptureQueryString()
			? masker.maskQueryString(query, globalOptions())
			: null);
		builder.service(properties.getServiceName(), context.instanceId());
		builder.actionType(io.github.pkyit.auditlog.spring.method.ActionTypes.parse(
			properties.getCollector().getMethod().getDefaultActionType()));
		builder.outcome(Outcome.UNKNOWN);
		builder.transactional(false);
		builder.rolledBack(false);

		// 先按边界通道兜底：方法级元数据由语义通道在解析到 @AuditLog 后补入
		context.setMethodMetadata(null);
		context.builder().tag("capturePlan", "boundary");
		metrics.increment(AuditMetricNames.COLLECTED);
		return context;
	}

	private static String environmentOf(AuditRequestView request) {
		return request == null ? "unknown" : request.environment();
	}

	// ------------------------------------------------------------------
	// 阶段二：语义填充（HandlerMethod）
	// ------------------------------------------------------------------

	/**
	 * 由语义通道调用：读取 {@code @AuditLog} 元数据并写入上下文。
	 *
	 * @return 命中的元数据；未命中返回 {@code null}
	 */
	public AuditMethodMetadata applySemantic(AuditRecordContext context, HandlerMethod handlerMethod) {
		return applySemantic(context, handlerMethod, null, null);
	}

	/**
	 * 由语义通道调用：读取 {@code @AuditLog} 元数据，并同时解析请求参数形式的资源 ID。
	 *
	 * <p>{@code resourceIdParam} 的解析必须放在这里，不能只放在 {@link #finishSemantic}：
	 * {@code finishSemantic} 依赖「方法执行结束」这一钩子，而反应式下没有等价的可靠钩子
	 * （WebFlux 的 HandlerAdapter 返回的 Mono 由框架内部订阅，订阅时机与 Servlet 的同步调用
	 * 不同，挂在其上的回调不保证被触发）。只依赖请求、不依赖方法实参的解析放在此处，
	 * 两种环境都能拿到请求视图，语义因此一致。
	 *
	 * <p>{@code finishSemantic} 保留用于解析依赖方法实参的表达式形式
	 * （{@code resourceId="#args[0]"}、{@code resourceId="#orderId"}）与实参序列化；
	 * 本方法只处理 {@code resourceIdParam}。
	 *
	 * @param request 请求视图；为 {@code null} 时只做注解元数据填充，不解析请求参数
	 * @param args    方法实参；为 {@code null} 时不解析实参形式的资源 ID
	 */
	public AuditMethodMetadata applySemantic(AuditRecordContext context, HandlerMethod handlerMethod,
			AuditRequestView request, Object[] args) {
		context.markSemanticCaptureAttempted();
		AuditMethodMetadata metadata = methodRegistry == null ? null : methodRegistry.find(handlerMethod);
		if (metadata == null) {
			// 未标注注解不是错误：边界通道已兜底采集。这里仍记一次未命中指标，
			// 使「注解写了却不在采集范围」这类配置问题可被观测。
			metrics.increment(AuditMetricNames.SKIPPED);
			if (properties.getValidation().isWarnOnUnmatchedHandler()) {
				log.debug("[audit-log] Handler 未标注 @AuditLog，仅按边界通道采集：{}.{}",
					handlerMethod.getBeanType().getSimpleName(), handlerMethod.getMethod().getName());
			}
			return null;
		}
		context.setMethodMetadata(metadata);
		AuditRecord.Builder builder = context.builder();
		builder.operation(metadata.operation());
		if (metadata.actionType() != ActionType.UNKNOWN) {
			builder.actionType(metadata.actionType());
		}
		builder.resource(emptyToNull(metadata.resourceType()), emptyToNull(metadata.resourceId()));
		if (!metadata.tags().isEmpty()) {
			builder.tags(metadata.tags());
		}
		// 请求参数形式的资源 ID：只依赖请求，因此在两种环境下都能在此确定
		if (request != null) {
			String fromParam = resolveResourceIdFromRequestParam(metadata, request);
			if (fromParam != null) {
				builder.resource(emptyToNull(metadata.resourceType()), fromParam);
			}
		}
		builder.tag("capturePlan", "semantic");
		return metadata;
	}

	/** 仅按 {@code resourceIdParam} 从请求参数解析资源 ID；不匹配返回 {@code null}。 */
	private String resolveResourceIdFromRequestParam(AuditMethodMetadata metadata, AuditRequestView request) {
		String param = metadata.resourceIdParam();
		if (param == null || param.isBlank()) {
			return null;
		}
		try {
			String value = request.parameter(param);
			return value == null || value.isBlank() ? null : truncate(value, 256);
		}
		catch (Throwable error) {
			reportFailure("resource", "资源 ID 解析失败", error);
			return null;
		}
	}

	/**
	 * 由语义通道在方法执行结束后调用：写入资源 ID 解析结果，并按配置序列化方法实参。
	 *
	 * <p>上下文未命中 {@code @AuditLog} 元数据时直接返回，不做任何写入。
	 *
	 * @param request  请求视图；为 {@code null} 时跳过资源 ID 解析
	 * @param args     方法实参；{@code serialize-arguments=false} 时不被序列化
	 * @param duration 方法执行毫秒数；记录中的耗时取自 {@link AuditRecordContext#elapsedMillis()}，
	 *                 与本参数无关
	 */
	public void finishSemantic(AuditRecordContext context, AuditRequestView request, Object[] args, long duration) {
		AuditMethodMetadata metadata = context.getMethodMetadata();
		if (metadata == null) {
			return;
		}
		AuditRecord.Builder builder = context.builder();
		String resourceId = request == null ? null : resolveResourceId(metadata, request, args);
		if (resourceId != null) {
			builder.resource(emptyToNull(metadata.resourceType()), resourceId);
		}
		if (properties.getCollector().getMethod().isSerializeArguments()) {
			builder.requestPayload(serializeArguments(args, metadata));
		}
	}

	// ------------------------------------------------------------------
	// 阶段三：请求结束，判定结果并发布
	// ------------------------------------------------------------------

	/**
	 * 填充真实结果并发布唯一一条记录。
	 *
	 * <p>重复调用是幂等的：上下文已发布时直接返回，不重复提交。事务已回滚且
	 * {@code drop-on-rollback=true} 时不发布记录，只做指标计数。
	 */
	public void finish(AuditRecordContext context, AuditRequestView request, AuditResponseView response,
			Throwable error) {
		if (context.isPublished()) {
			return;
		}
		AuditRecord.Builder builder = context.builder();
		int status = response == null ? AuditResponseView.UNKNOWN_STATUS : response.status();
		AuditMethodMetadata metadata = context.getMethodMetadata();

		builder.receivedTime(java.time.Instant.now());
		// 异常沿过滤器链向上抛出时容器尚未渲染错误页，此刻状态码仍是占位的 200，
		// 而 outcome 已判定为 FAILURE，会产生「200 + FAILURE」这种自相矛盾的记录，
		// 下游按 status 统计会误判为成功。
		// 因此异常透传且状态码仍为 2xx 时把 httpStatus 置空（未知），不写入一个已知错误的数字。
		boolean statusUnknown = status == AuditResponseView.UNKNOWN_STATUS;
		boolean statusIsMisleadingPlaceholder =
			error != null && status >= 200 && status < 300;
		builder.httpStatus(statusUnknown || statusIsMisleadingPlaceholder
			? null : Integer.valueOf(status));
		builder.durationMillis(context.elapsedMillis());
		builder.outcome(decideOutcome(status, error));
		if (statusIsMisleadingPlaceholder) {
			// 让下游能区分"确实没状态码"与"状态码是占位值"
			builder.tag("httpStatusNote", "异常透传，容器尚未渲染错误响应，状态码未知");
		}

		// 事务结果对齐：回滚意味着操作未生效，默认不产生记录（避免"记录了没发生的事"）
		if (context.getTransactionResult() == TransactionAuditTracker.TransactionResult.ROLLED_BACK) {
			if (properties.isDropOnRollback()) {
				metrics.increment(AuditMetricNames.SKIPPED);
				log.info("[audit-log] 事务已回滚且 drop-on-rollback=true，不产生审计记录 uri={} operation={}",
					request == null ? null : request.uri(),
					context.getMethodMetadata() == null ? null : context.getMethodMetadata().operation());
				return;
			}
			builder.outcome(Outcome.ROLLBACK);
			builder.rolledBack(true);
		}

		if (error != null) {
			builder.error(ErrorInfo.of(error, properties.getMasking().isIncludeStackTrace(),
				properties.getMasking().getMaxErrorMessageLength()));
		}
		// 业务码无法从响应头/状态码推导，因此留空，由使用者的 AuditStore 或自定义 SPI 补充：
		// 留空可被下游识别为未知，填入猜测值会被当作事实使用。
		builder.businessResult(BusinessResult.EMPTY);

		CapturePlan plan = planOf(context, metadata);
		CaptureOptions options = optionsOf(plan);
		if (plan.captureRequestPayload()) {
			builder.requestPayload(requestPayload(context, request, options));
		}
		if (plan.captureHeaders()) {
			builder.tag("headers", masker.maskHeaders(headersOf(request), options).toString());
		}
		if (plan.captureResponsePayload()) {
			builder.responsePayload(responsePayload(response, options));
		}
		// transactional/rolledBack 由语义通道在事务内注册同步回调时置位（见 AuditLogHandlerMethod）
		AuditRecord record = builder.build();
		context.markPublished();

		boolean accepted = pipeline.submit(record);
		if (!accepted) {
			// submit 内部已计数与告警；这里只补充一条便于定位的上下文
			log.debug("[audit-log] 审计记录未被接受 uri={} id={}", record.getRequestUri(), record.getId());
		}
	}

	private Outcome decideOutcome(int status, Throwable error) {
		if (error != null) {
			return Outcome.FAILURE;
		}
		if (status == 401 || status == 403) {
			return Outcome.DENIED;
		}
		if (status == 400 || status == 405 || status == 415 || status == 422) {
			return Outcome.INVALID;
		}
		if (status == 404) {
			return Outcome.INVALID;
		}
		if (status >= 500) {
			return Outcome.FAILURE;
		}
		if (status >= 200 && status < 400) {
			return Outcome.SUCCESS;
		}
		return Outcome.UNKNOWN;
	}

	// ------------------------------------------------------------------
	// 采集计划与选项
	// ------------------------------------------------------------------

	private CapturePlan planOf(AuditRecordContext context, AuditMethodMetadata metadata) {
		AuditLogProperties.Masking masking = properties.getMasking();
		// 必须带上全局配置的字段脱敏规则：只带方法级 sensitiveParams 会使
		// audit.log.masking.sensitive-fields 完全不生效，出现「配置了敏感字段却依然原文落库」
		// 的静默泄漏。
		List<FieldMaskRule> fieldRules = new ArrayList<>(masking.toFieldRules());
		if (metadata != null && metadata.sensitiveParams() != null) {
			for (String field : metadata.sensitiveParams()) {
				if (field != null && !field.isBlank()) {
					fieldRules.add(FieldMaskRule.full(field.trim()));
				}
			}
		}
		// 同时把规则交给脱敏器作为「基础规则」，覆盖非 JSON 载荷与查询串等路径
		CapturePlan plan = metadata == null
			? CapturePlan.boundaryOnly(masking.isCaptureQueryString(), false, fieldRules, masking.toRegexRules())
			: new CapturePlan(
				true,
				metadata.logRequestParams(),
				metadata.logResponseParams() && properties.getCollector().getMethod().isCaptureResponseBody(),
				metadata.logHeaders(),
				masking.isCaptureQueryString(),
				metadata,
				fieldRules,
				masking.toRegexRules());
		return plan;
	}

	private CaptureOptions optionsOf(CapturePlan plan) {
		AuditLogProperties.Masking masking = properties.getMasking();
		return new CaptureOptions(
			plan.captureRequestPayload(),
			plan.captureResponsePayload(),
			plan.captureHeaders(),
			masking.getMaxBodyLength(),
			masking.getMaxDepth(),
			masking.isIncludeStackTrace(),
			masking.getMaxErrorMessageLength(),
			plan.fieldRules(),
			plan.regexRules(),
			masking.sensitiveHeadersLowercased(),
			java.util.Set.of());
	}

	private CaptureOptions globalOptions() {
		AuditLogProperties.Masking masking = properties.getMasking();
		return new CaptureOptions(false, false, false, masking.getMaxBodyLength(), masking.getMaxDepth(),
			masking.isIncludeStackTrace(), masking.getMaxErrorMessageLength(), masking.toFieldRules(),
			masking.toRegexRules(), masking.sensitiveHeadersLowercased(), java.util.Set.of());
	}

	// ------------------------------------------------------------------
	// 载荷构造
	// ------------------------------------------------------------------

	private Payload requestPayload(AuditRecordContext context, AuditRequestView request, CaptureOptions options) {
		try {
			String cached = request.cachedBody();
			if (cached != null && !cached.isEmpty()) {
				Payload payload = serializer.wrapText(cached, options, looksJson(cached) ? "json" : "text");
				return masker.maskPayload(payload, options);
			}
			String query = request.queryString();
			if (query != null && !query.isEmpty() && properties.getMasking().isCaptureQueryString()) {
				String masked = masker.maskQueryString(query, options);
				return new Payload("query", masked, masked.length(), false);
			}
			return Payload.EMPTY;
		}
		catch (Throwable error) {
			reportFailure("payload", "请求载荷采集失败", error);
			return Payload.EMPTY;
		}
	}

	private Payload responsePayload(AuditResponseView response, CaptureOptions options) {
		try {
			if (response == null || response.status() == AuditResponseView.UNKNOWN_STATUS) {
				return Payload.EMPTY;
			}
			// 响应正文需要包装输出流（Servlet）或订阅数据流（Reactive）才能采集，在流式与
			// 大响应场景会改变原有行为，因此这里只记录响应元信息（status），
			// 正文采集留给扩展点，避免为了审计而破坏响应。
			String summary = "{\"status\":" + response.status() + "}";
			return serializer.wrapText(summary, options, "json");
		}
		catch (Throwable error) {
			reportFailure("payload", "响应载荷采集失败", error);
			return Payload.EMPTY;
		}
	}

	private Payload serializeArguments(Object[] args, AuditMethodMetadata metadata) {
		if (args == null || args.length == 0) {
			return Payload.EMPTY;
		}
		try {
			Map<String, Object> values = new LinkedHashMap<>();
			List<String> names = metadata.argumentNames();
			for (int index = 0; index < args.length; index++) {
				Object argument = args[index];
				if (isFrameworkType(argument)) {
					continue;
				}
				String name = index < names.size() && !names.get(index).isEmpty() ? names.get(index)
					: ("arg" + index);
				values.put(name, argument);
			}
			CaptureOptions options = optionsOf(CapturePlan.boundaryOnly(false, false,
				properties.getMasking().toFieldRules(), properties.getMasking().toRegexRules()));
			Payload payload = serializer.serialize(values, options, "arguments");
			return masker.maskPayload(payload, options);
		}
		catch (Throwable error) {
			reportFailure("arguments", "方法实参序列化失败", error);
			return Payload.EMPTY;
		}
	}

	/**
	 * 跳过框架类型参数。
	 *
	 * <p>这些参数不是业务输入，序列化它们会产生噪音、超长字符串甚至异常
	 * （例如 {@code MultipartFile}、{@code InputStream}）。
	 */
	private boolean isFrameworkType(Object argument) {
		if (argument == null) {
			return false;
		}
		return argument instanceof jakarta.servlet.ServletRequest
			|| argument instanceof jakarta.servlet.ServletResponse
			|| argument instanceof java.security.Principal
			|| argument instanceof java.io.InputStream
			|| argument instanceof java.io.OutputStream
			|| argument instanceof java.io.Reader
			|| argument instanceof java.io.Writer
			|| argument instanceof java.util.Locale
			|| argument instanceof java.util.TimeZone
			|| argument.getClass().getName().startsWith("org.springframework.ui.")
			|| argument.getClass().getName().startsWith("org.springframework.validation.")
			|| argument.getClass().getName().startsWith("org.springframework.web.multipart.");
	}

	private java.lang.reflect.Method findMethod(AuditMethodMetadata metadata, int argumentCount) {
		try {
			Class<?> type = Class.forName(metadata.controllerClassName());
			for (java.lang.reflect.Method method : type.getMethods()) {
				if (method.getName().equals(metadata.methodName()) && method.getParameterCount() == argumentCount) {
					return method;
				}
			}
			for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
				if (method.getName().equals(metadata.methodName()) && method.getParameterCount() == argumentCount) {
					return method;
				}
			}
		}
		catch (Throwable ignored) {
			// 类加载失败不影响主流程：资源 ID 解析会退化为"无法解析"
		}
		return null;
	}

	// ------------------------------------------------------------------
	// 解析辅助
	// ------------------------------------------------------------------

	private PrincipalInfo resolvePrincipal(PrincipalResolver.ResolutionContext context) {
		for (PrincipalResolver resolver : principalResolvers) {
			try {
				Optional<PrincipalInfo> resolved = resolver.resolve(context);
				if (resolved.isPresent()) {
					return resolved.get();
				}
			}
			catch (Throwable error) {
				reportFailure("principal", "主体解析器失败：" + resolver.getClass().getName(), error);
			}
		}
		return PrincipalInfo.anonymous();
	}

	private String resolveTraceId(PrincipalResolver.ResolutionContext context) {
		try {
			String traceId = traceIdResolver == null ? null : traceIdResolver.resolveTraceId(context);
			if (traceId != null && !traceId.isBlank()) {
				return traceId;
			}
		}
		catch (Throwable error) {
			reportFailure("trace", "链路 ID 解析失败", error);
		}
		// 无外部链路 ID 时生成内部 ID，保证记录自身可被追踪（而不是留空无法关联）
		return "local-" + AuditRecordContext.nextId();
	}

	private String resolveTenant(PrincipalResolver.ResolutionContext context) {
		try {
			return tenantResolver == null ? null : tenantResolver.resolve(context).orElse(null);
		}
		catch (Throwable error) {
			reportFailure("tenant", "租户解析失败", error);
			return null;
		}
	}

	private String resolveClientIp(AuditRequestView request) {
		try {
			String remoteAddress = request.remoteAddress();
			if (clientIpResolver == null) {
				return remoteAddress;
			}
			boolean trustedProxy = clientIpResolver instanceof DefaultClientIpResolver defaultResolver
				&& defaultResolver.isTrusted(remoteAddress);
			return clientIpResolver.resolve(new ClientIpResolver.ResolutionContext() {
				@Override
				public String getRemoteAddress() {
					return remoteAddress;
				}

				@Override
				public String getHeader(String name) {
					return request.header(name);
				}

				@Override
				public boolean isTrustedProxy() {
					return trustedProxy;
				}
			}).orElse(remoteAddress);
		}
		catch (Throwable error) {
			reportFailure("clientIp", "客户端 IP 解析失败", error);
			return request.remoteAddress();
		}
	}

	private PrincipalResolver.ResolutionContext resolutionContextOf(AuditRequestView request) {
		return new PrincipalResolver.ResolutionContext() {
			@Override
			public String getHeader(String name) {
				return request.header(name);
			}

			@Override
			public String getRequestUri() {
				return request.uri();
			}

			@Override
			public String getHttpMethod() {
				return request.method();
			}

			@Override
			public String getAttribute(String name) {
				Object value = request.attribute(name);
				return value == null ? null : String.valueOf(value);
			}
		};
	}

	private String resolveResourceId(AuditMethodMetadata metadata, AuditRequestView request, Object[] args) {
		if (metadata.resourceIdParam() != null && !metadata.resourceIdParam().isBlank()) {
			String value = request.parameter(metadata.resourceIdParam());
			if (value != null && !value.isBlank()) {
				return truncate(value, 256);
			}
		}
		String expression = metadata.resourceId();
		if (expression == null || expression.isBlank()) {
			return null;
		}
		String trimmed = expression.trim();
		if (trimmed.startsWith("#")) {
			String name = trimmed.substring(1);
			if (name.startsWith("args[")) {
				try {
					int index = Integer.parseInt(name.substring(5, name.indexOf(']')));
					if (args != null && index < args.length) {
						return truncate(String.valueOf(args[index]), 256);
					}
				}
				catch (RuntimeException ignored) {
					return null;
				}
			}
			String fromRequest = request.parameter(name);
			if (fromRequest != null && !fromRequest.isBlank()) {
				return truncate(fromRequest, 256);
			}
			// 简单表达式回退：把 #name 当作方法参数名解析
			Object value = metadata.argumentValue(name, args);
			if (value != null) {
				return truncate(String.valueOf(value), 256);
			}
			return null;
		}
		return truncate(trimmed, 256);
	}

	private Map<String, String> headersOf(AuditRequestView request) {
		Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		request.allHeaders().forEach((name, values) -> {
			if (name != null) {
				headers.put(name, values == null ? "" : String.join(", ", values));
			}
		});
		return headers;
	}

	private String sessionIdOf(AuditRequestView request) {
		try {
			// 会话 ID 只记录不可逆摘要，避免把会话凭据写入审计库造成二次泄漏。
			// 优先用容器给出的会话 ID；取不到时退回 JSESSIONID Cookie（Servlet 常见形态）。
			String sessionId = request.sessionId();
			if (sessionId == null || sessionId.isBlank()) {
				sessionId = request.cookie("JSESSIONID");
			}
			if (sessionId == null || sessionId.isBlank()) {
				return null;
			}
			return "sha256:" + io.github.pkyit.auditlog.core.chain.Hasher
				.sha256(sessionId).substring(0, 16);
		}
		catch (Throwable ignored) {
			// 无会话信息不影响审计
			return null;
		}
	}

	/**
	 * 报告审计链路自身的失败。
	 *
	 * <p>只做指标计数与失败回调，不抛异常：审计失败不得影响业务，失败处理器自身抛出的异常
	 * 同样被吞掉。
	 *
	 * @param stage   失败发生的位置标识（如 {@code payload}、{@code principal}）
	 * @param message 面向使用者的失败描述
	 * @param error   原始异常
	 */
	public void reportFailure(String stage, String message, Throwable error) {
		try {
			metrics.increment(AuditMetricNames.COLLECT_ERROR);
			failureHandler.onFailure(stage, message, List.of(), error);
		}
		catch (Throwable ignored) {
			// 失败处理器自身异常也不能外溢
		}
	}

	private static boolean looksJson(String value) {
		if (value == null) {
			return false;
		}
		String trimmed = value.stripLeading();
		return (trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"));
	}

	private static String nullSafe(String value) {
		return value == null ? "" : value.replace("\"", "'");
	}

	private static String emptyToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static String truncate(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		return value.substring(0, max) + "...";
	}

	/** 对外暴露方法注册表（供管理端点使用）。 */
	public AuditMethodRegistry getMethodRegistry() {
		return methodRegistry;
	}

	/** 返回本次采集使用的发布管道。 */
	public AuditPipeline getPipeline() {
		return pipeline;
	}

	/** 返回当前生效的配置。 */
	public AuditLogProperties getProperties() {
		return properties;
	}

	/** 供测试与扩展使用的脱敏器。 */
	public AuditMasker getMasker() {
		return masker;
	}

	/** 供测试与扩展使用的序列化器。 */
	public AuditSerializer getSerializer() {
		return serializer;
	}

	/** 便捷方法：暴露租户解析（供自定义扩展）。 */
	public String currentTenant(AuditRequestView request) {
		return resolveTenant(resolutionContextOf(request));
	}

	/** 便捷方法：暴露主体解析（供自定义扩展）。 */
	public PrincipalInfo currentPrincipal(AuditRequestView request) {
		return resolvePrincipal(resolutionContextOf(request));
	}

	static String lower(String value) {
		return value == null ? null : value.toLowerCase(Locale.ROOT);
	}
}
