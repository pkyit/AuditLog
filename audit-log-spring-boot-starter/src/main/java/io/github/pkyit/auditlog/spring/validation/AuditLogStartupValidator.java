package io.github.pkyit.auditlog.spring.validation;

import io.github.pkyit.auditlog.core.api.AuditChain;
import io.github.pkyit.auditlog.core.api.AuditMetrics;
import io.github.pkyit.auditlog.core.api.AuditStore;
import io.github.pkyit.auditlog.core.store.LoggingAuditStore;
import io.github.pkyit.auditlog.spring.method.AuditMethodRegistry;
import io.github.pkyit.auditlog.spring.properties.AuditLogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动期校验：填充方法级元数据注册表，并校验配置与注解声明的一致性。
 *
 * <p>注解被标注却未进入采集集合、主体解析不到、存储未真正落盘等情形都不会让应用启动失败，
 * 也不会改变响应结果，只会在审计对账时才暴露。本类把这些情形前移到启动阶段：
 * 使用者的明确错误直接让启动失败，不符合合规要求的配置逐条告警。
 *
 * <p>本类承担两个职责：
 * <ol>
 *   <li>填充方法级元数据注册表：在容器刷新完成后（所有单例已就绪）才解析
 *       {@code RequestMappingHandlerMapping}。解析必须延后到该阶段，否则会在 Bean
 *       创建期触发 WebMvc 自动配置，进而形成循环依赖；</li>
 *   <li>校验并暴露问题：标注了注解却不被采集属于明确错误，默认让启动失败；
 *       能运行但不符合合规要求的配置（非持久化存储、未启用哈希链、会丢数据的背压策略）逐条告警。</li>
 * </ol>
 *
 * <p>失败与告警的划分由 {@code audit.log.validation.fail-on-mismatch} 控制，
 * 置为 {@code false} 时全部降级为告警，启动不会被中止。
 */
public class AuditLogStartupValidator implements SmartInitializingSingleton {

	private static final Logger log = LoggerFactory.getLogger(AuditLogStartupValidator.class);

	private final AuditLogProperties properties;
	private final AuditMethodRegistry registry;
	private final AuditStore store;
	private final AuditChain chain;
	private final AuditMetrics metrics;
	private final ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider;
	private final org.springframework.beans.factory.ListableBeanFactory beanFactory;
	private final ObjectProvider<io.github.pkyit.auditlog.spring.autoconfigure.AuditLogAnnotationProperties>
		annotationPropertiesProvider;

	/**
	 * 创建不含 {@code @EnableAuditLog} 属性来源的实例：注解侧全部按默认值处理。
	 *
	 * <p>此时注解侧视为开启，方法级采集是否生效只由配置项
	 * {@code audit.log.collector.method.enabled} 决定。
	 */
	public AuditLogStartupValidator(AuditLogProperties properties, AuditMethodRegistry registry,
			AuditStore store, AuditChain chain, AuditMetrics metrics,
			ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider,
			org.springframework.beans.factory.ListableBeanFactory beanFactory) {
		this(properties, registry, store, chain, metrics, handlerMappingProvider, beanFactory, null);
	}

	/**
	 * 创建完整实例。
	 *
	 * <p>允许 {@code metrics} 为 {@code null}，此时 {@link #describe()} 以 {@code none} 占位。
	 *
	 * @param annotationPropertiesProvider {@code @EnableAuditLog} 属性的来源；
	 *        为 {@code null} 时注解侧视为开启，采集行为只由配置项决定
	 */
	public AuditLogStartupValidator(AuditLogProperties properties, AuditMethodRegistry registry,
			AuditStore store, AuditChain chain, AuditMetrics metrics,
			ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider,
			org.springframework.beans.factory.ListableBeanFactory beanFactory,
			ObjectProvider<io.github.pkyit.auditlog.spring.autoconfigure.AuditLogAnnotationProperties>
				annotationPropertiesProvider) {
		this.properties = properties;
		this.registry = registry;
		this.store = store;
		this.chain = chain;
		this.metrics = metrics;
		this.handlerMappingProvider = handlerMappingProvider;
		this.beanFactory = beanFactory;
		this.annotationPropertiesProvider = annotationPropertiesProvider;
	}

	/**
	 * 在所有单例实例化完成后填充方法级元数据注册表，并执行启动期校验。
	 *
	 * <p>解析 {@code RequestMappingHandlerMapping} 必须等到该回调：提前到 Bean 初始化阶段
	 * 会触发 WebMvc 自动配置并形成循环依赖。本方法由容器在单例就绪后调用一次。
	 *
	 * @throws IllegalStateException 存在明确不一致的配置且未降级为告警；
	 *         消息中包含全部失败项与降级开关名
	 */
	@Override
	public void afterSingletonsInstantiated() {
		// 1) 填充注册表：此时 WebMvc 已完成初始化，不会形成循环依赖
		//
		// 注册表与环境相关：只有 Servlet 环境才做启动期全量扫描。WebFlux 环境不注册
		// ServletMethodRegistry，其语义通道在请求期从 ServerWebExchange 取已匹配的
		// HandlerMethod，不需要启动扫描。因此这里必须容忍注册表不支持扫描，
		// 不能假定所有实现都提供扫描入口。
		if (registry instanceof io.github.pkyit.auditlog.spring.method.ServletMethodRegistry servletRegistry) {
			RequestMappingHandlerMapping handlerMapping = resolveHandlerMapping();
			// 注解属性 methodCollector 与配置项 audit.log.collector.method.enabled 共同约束采集：
			// 两者都开启时才采集，任一关闭即关闭，配置项的显式关闭不会被注解覆盖。
			// 未引入 @EnableAuditLog 时注解侧视为开启，等同于只由配置项决定。
			boolean methodCollectorEnabled = properties.getCollector().getMethod().isEnabled()
				&& annotationMethodCollectorEnabled();
			servletRegistry.populate(handlerMapping, methodCollectorEnabled);
			log.info("[audit-log] 方法级元数据注册完成：扫描 Handler {} 个，命中 @AuditLog {} 个",
				servletRegistry.scannedHandlerCount(), servletRegistry.collectedMethodCount());
		}
		else {
			log.info("[audit-log] 当前环境不做方法级启动扫描（注册表类型={}）",
				registry.getClass().getSimpleName());
		}

		// 2) 校验
		List<String> failures = new ArrayList<>();
		List<String> warnings = new ArrayList<>();

		validateAnnotationConsistency(failures, warnings);
		validateCollectorCoverage(warnings);
		validateParameterNames(warnings);
		validateCompliance(warnings);
		validateBackpressure(warnings);
		validateIdentity(warnings);
		validateBoundaryPatterns(warnings);
		validateAnnotationScope(warnings);

		warnings.forEach(warning -> log.warn("[audit-log] 启动校验：{}", warning));
		log.info("[audit-log] 已启用：存储={} 背压策略={} 哈希链={} 边界通道={} 方法级采集={} 命中注解方法={}",
			store.getName(), properties.getPipeline().getStrategy(),
			chain.isEnabled() ? "启用" : "未启用",
			properties.getCollector().getBoundary().isEnabled() ? "启用" : "关闭",
			properties.getCollector().getMethod().isEnabled() ? "启用" : "关闭",
			registry.collectedMethodCount());

		if (!failures.isEmpty()) {
			throw new IllegalStateException("[audit-log] 启动校验失败（可设置 "
				+ "audit.log.validation.fail-on-mismatch=false 将其降级为告警）：\n - "
				+ String.join("\n - ", failures));
		}
	}

	/**
	 * 读取注解上的 {@code methodCollector}，用于与配置项共同判定是否采集方法级元数据。
	 *
	 * @return 注解侧是否开启采集；未引入 {@code @EnableAuditLog} 时返回 {@code true}
	 */
	private boolean annotationMethodCollectorEnabled() {
		if (annotationPropertiesProvider == null) {
			return true;
		}
		io.github.pkyit.auditlog.spring.autoconfigure.AuditLogAnnotationProperties annotationProperties =
			annotationPropertiesProvider.getIfAvailable();
		return annotationProperties == null || annotationProperties.isMethodCollector();
	}

	/**
	 * 校验 {@code @EnableAuditLog(scanBasePackages=...)} 的声明与实际采集范围是否一致。
	 *
	 * <p>方法级元数据来自 Spring 真实的 HandlerMapping，不能按注解包名过滤：过滤会丢弃
	 * 包外的 {@code @AuditLog} 方法，造成注解方法不被采集且无人察觉，
	 * 后果比"扫描范围未生效"更严重。
	 *
	 * <p>声明范围与实际命中的 Handler 不一致时按告警输出，由使用者修正配置；
	 * 该属性只参与校验，不改变采集行为，也不静默忽略。
	 *
	 * @param warnings 告警收集器，不一致项以文本形式追加到该列表
	 */
	private void validateAnnotationScope(List<String> warnings) {
		if (annotationPropertiesProvider == null) {
			return;
		}
		io.github.pkyit.auditlog.spring.autoconfigure.AuditLogAnnotationProperties annotationProperties =
			annotationPropertiesProvider.getIfAvailable();
		if (annotationProperties == null) {
			return;
		}
		List<String> packages = annotationProperties.getBasePackages();
		log.info("[audit-log] @EnableAuditLog 声明：扫描包={} 方法级采集={} 默认动作类型={}",
			packages.isEmpty() ? "(未声明)" : packages,
			annotationProperties.isMethodCollector(),
			annotationProperties.getDefaultActionType());
		if (packages.isEmpty()) {
			return;
		}
		// 实际被方法级采集的 Handler 若落在声明包之外，说明"声明的范围"与"实际采集"不一致
		List<String> outside = registry.collectedKeys().stream()
			.filter(key -> packages.stream().noneMatch(key::startsWith))
			.limit(10)
			.toList();
		if (!outside.isEmpty()) {
			warnings.add("@EnableAuditLog(scanBasePackages=" + packages + ") 声明了扫描范围，"
				+ "但实际采集到范围外的 Handler（方法级元数据来源于 Spring HandlerMapping，"
				+ "不按注解包名过滤，以免静默丢弃注解方法）：" + outside);
		}
	}

	/**
	 * 选取 Spring MVC 的主请求映射。
	 *
	 * <p>不能依赖「唯一 Bean」语义：引入 Actuator 后容器中会多出
	 * {@code controllerEndpointHandlerMapping}，此时 {@code getIfUnique()} 返回 {@code null}，
	 * 使元数据注册表为空、所有 {@code @AuditLog} 方法失去元数据。
	 * 因此按 Bean 名优先、其次精确类型、最后 order 的顺序确定性选取。
	 *
	 * @return 主 {@code RequestMappingHandlerMapping}；容器中不存在或解析失败时返回 {@code null}，
	 *         此时方法级元数据不可用
	 */
	private RequestMappingHandlerMapping resolveHandlerMapping() {
		try {
			List<RequestMappingHandlerMapping> candidates =
				handlerMappingProvider.orderedStream().collect(java.util.stream.Collectors.toList());
			if (candidates.isEmpty()) {
				log.warn("[audit-log] 容器中没有 RequestMappingHandlerMapping，方法级元数据将不可用"
					+ "（纯非 Web 场景可忽略此告警）");
				return null;
			}
			for (RequestMappingHandlerMapping candidate : candidates) {
				for (String name : beanFactory.getBeanNamesForType(RequestMappingHandlerMapping.class)) {
					try {
						if (beanFactory.getBean(name) == candidate
								&& "requestMappingHandlerMapping".equals(name)) {
							return candidate;
						}
					}
					catch (Throwable ignored) {
						// 单个 Bean 名解析失败不影响其余候选的比对
					}
				}
			}
			// 回退：优先 exact type 为 RequestMappingHandlerMapping 的那个
			// （Actuator 的控制器端点映射是其子类，不应抢占主映射）
			for (RequestMappingHandlerMapping candidate : candidates) {
				if (RequestMappingHandlerMapping.class.equals(candidate.getClass())) {
					return candidate;
				}
			}
			RequestMappingHandlerMapping fallback = candidates.get(0);
			log.warn("[audit-log] 未能确定主 RequestMappingHandlerMapping，回退使用 {}，方法级元数据可能不完整",
				fallback.getClass().getName());
			return fallback;
		}
		catch (Throwable error) {
			log.warn("[audit-log] 解析 RequestMappingHandlerMapping 失败，方法级元数据将不可用：{}",
				error.toString());
			return null;
		}
	}

	/**
	 * 校验边界通道的 URL 模式。
	 *
	 * <p>Ant 语法中 {@code /*} 只匹配单层路径，写成它会使多层级接口（如 {@code /user/query}）
	 * 被静默跳过，属于"配置看似生效、实际不采集任何请求"的情形。
	 *
	 * @param warnings 告警收集器，仅当边界通道开启且声明了 include-patterns 时才追加
	 */
	private void validateBoundaryPatterns(List<String> warnings) {
		if (!properties.getCollector().getBoundary().isEnabled()) {
			return;
		}
		List<String> includes = properties.getCollector().getBoundary().getIncludePatterns();
		if (includes == null || includes.isEmpty()) {
			return;
		}
		for (String pattern : includes) {
			String trimmed = pattern == null ? "" : pattern.trim();
			if (trimmed.equals("/*")) {
				warnings.add("边界通道 include-patterns 含 \"/*\"，在 Ant 语法中它只匹配单层路径，"
					+ "多层级接口（如 /user/query）将被静默跳过。请改为 \"/**\"");
			}
		}
	}

	private void validateAnnotationConsistency(List<String> failures, List<String> warnings) {
		if (registry.excludedHandlers().isEmpty()) {
			return;
		}
		String detail = "以下 Handler 标注了 @AuditLog 但不会被采集：" + registry.excludedHandlers()
			+ "。请移除 ignore=true，或开启 audit.log.collector.method.enabled";
		if (properties.getValidation().isFailOnMismatch()) {
			failures.add(detail);
		}
		else {
			warnings.add(detail);
		}
	}

	private void validateCollectorCoverage(List<String> warnings) {
		boolean boundary = properties.getCollector().getBoundary().isEnabled();
		boolean method = properties.getCollector().getMethod().isEnabled();
		if (!method) {
			warnings.add("方法级采集已关闭（collector.method.enabled=false）：审计记录将只包含边界信息"
				+ "（来源、状态码、耗时），缺少操作名、动作类型与资源标识");
		}
		if (!boundary && !method) {
			warnings.add("边界通道与方法级采集均已关闭，审计功能实际不会产生任何记录");
		}
	}

	/**
	 * 校验编译期是否保留了方法参数名（{@code -parameters}）。
	 *
	 * <p>审计框架与 Spring MVC 都依赖参数名：前者用于 {@code resourceIdParam} 与实参表达式
	 * （{@code resourceId="#userId"}）解析，后者用于 {@code @PathVariable}/{@code @RequestParam}
	 * 的名称绑定。缺少该标志时不会编译失败，而是在运行期表现为语义字段缺失，
	 * 或"parameter name information not available"导致的 400，排查成本很高。
	 *
	 * <p>因此在这里主动检测并明确告知需要开启的编译选项。
	 */
	private void validateParameterNames(List<String> warnings) {
		int inspected = 0;
		for (String key : registry.collectedKeys()) {
			if (inspected >= 5) {
				break;
			}
			java.lang.reflect.Method method = methodOf(key);
			if (method == null || method.getParameterCount() == 0) {
				continue;
			}
			inspected++;
			boolean namesPresent = java.util.Arrays.stream(method.getParameters())
				.allMatch(java.lang.reflect.Parameter::isNamePresent);
			if (!namesPresent) {
				warnings.add("编译产物未保留方法参数名（缺少 javac 的 -parameters 选项）："
					+ "resourceIdParam 与 resourceId=\"#参数名\" 将无法解析，审计记录会缺失资源标识。"
					+ "请在 maven-compiler-plugin 中设置 <parameters>true</parameters> 后重新编译");
				return;
			}
		}
	}

	/** 由注册表 key（{@code 类名#方法名(参数类型)}）还原方法，用于参数名检测。 */
	private java.lang.reflect.Method methodOf(String key) {
		int hash = key.indexOf('#');
		int paren = key.indexOf('(');
		if (hash <= 0 || paren <= hash) {
			return null;
		}
		String className = key.substring(0, hash);
		String methodName = key.substring(hash + 1, paren);
		try {
			Class<?> type = Class.forName(className, false, getClass().getClassLoader());
			for (java.lang.reflect.Method candidate : type.getMethods()) {
				if (candidate.getName().equals(methodName)) {
					return candidate;
				}
			}
		}
		catch (Throwable ignored) {
			// 类不可加载时跳过该项检测：这是校验增强，不应影响启动
		}
		return null;
	}

	private void validateCompliance(List<String> warnings) {
		if (store instanceof LoggingAuditStore && properties.getValidation().isWarnOnNonCompliantStore()) {
			warnings.add("当前审计存储为 logging（写入应用日志），不满足合规留存要求："
				+ "会与应用日志混存、随日志滚动被删除。生产环境请设置 "
				+ "audit.log.store.type=json-file 或 jdbc");
		}
		if (!chain.isEnabled()) {
			warnings.add("哈希链未启用：审计历史可被拥有写权限者静默修改而无法察觉。"
				+ "如需防篡改能力请设置 audit.log.chain.enabled=true 并配置 secret");
		}
		// capture-response-body 开关不会改变实际采集内容：当前实现只能拿到响应摘要
		if (properties.getCollector().getMethod().isCaptureResponseBody()) {
			warnings.add("已开启 collector.method.capture-response-body，但当前实现只记录响应摘要"
				+ "（contentType/status），拿不到响应正文 —— 这是尚未实现的已知局限，"
				+ "开启该开关不会改变实际采集内容。请勿据此认为响应正文已被记录");
		}
	}

	private void validateBackpressure(List<String> warnings) {
		switch (properties.getPipeline().getStrategy()) {
			case DROP_OLDEST, DROP_NEWEST -> warnings.add("背压策略为 "
				+ properties.getPipeline().getStrategy()
				+ "：队列打满时审计记录会真实丢失，请确保已对 audit.pipeline.dropped 指标配置告警");
			case BLOCK -> warnings.add("背压策略为 BLOCK：队列打满时会阻塞业务线程，"
				+ "审计写入能力将直接影响业务 SLA");
			case FAIL_FAST -> warnings.add("背压策略为 FAIL_FAST：审计不可用时会直接中断业务请求"
				+ "（以可用性换一致性，请确认这是有意选择）");
			case SPILL -> log.info("[audit-log] 背压策略为 SPILL（推荐）：队列打满时降级写入本地兜底日志，"
				+ "既不丢数据也不阻塞业务");
		}
	}

	private void validateIdentity(List<String> warnings) {
		if (properties.getIdentity().isTrustProxyHeaders()
				&& properties.getIdentity().getTrustedProxies().isEmpty()) {
			warnings.add("已开启 trust-proxy-headers 但 trusted-proxies 为空："
				+ "X-Forwarded-For 可被客户端伪造，代理头将被忽略，仅使用 TCP 层地址");
		}
		if (properties.getIdentity().isHeaderPrincipalEnabled()) {
			warnings.add("已开启请求头主体解析：审计主体标记为 TRUSTED_HEADER，"
				+ "其可信度完全取决于上游网关，请确保应用端口不可被外部直连");
		}
	}

	/**
	 * 供管理端点使用的校验快照。
	 *
	 * @return 单行文本，依次包含扫描 Handler 数、采集方法数、排除数、存储名、哈希链开关，
	 *         以及 metrics 实现名（{@code metrics} 为 {@code null} 时为 {@code none}）
	 */
	public String describe() {
		return "handler=" + registry.scannedHandlerCount()
			+ ", collected=" + registry.collectedMethodCount()
			+ ", excluded=" + registry.excludedHandlers().size()
			+ ", store=" + store.getName()
			+ ", chain=" + chain.isEnabled()
			+ ", metrics=" + (metrics == null ? "none" : metrics.getClass().getSimpleName());
	}
}
