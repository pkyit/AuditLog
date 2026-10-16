package io.github.pkyit.auditlog.spring.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pkyit.auditlog.core.api.AuditChain;
import io.github.pkyit.auditlog.core.api.AuditCodec;
import io.github.pkyit.auditlog.core.api.AuditFailureHandler;
import io.github.pkyit.auditlog.core.api.AuditJournal;
import io.github.pkyit.auditlog.core.api.AuditMasker;
import io.github.pkyit.auditlog.core.api.AuditMetrics;
import io.github.pkyit.auditlog.core.api.AuditSerializer;
import io.github.pkyit.auditlog.core.api.AuditStore;
import io.github.pkyit.auditlog.core.api.ClientIpResolver;
import io.github.pkyit.auditlog.core.api.PrincipalResolver;
import io.github.pkyit.auditlog.core.api.TenantResolver;
import io.github.pkyit.auditlog.core.api.TraceIdResolver;
import io.github.pkyit.auditlog.core.chain.HmacAuditChain;
import io.github.pkyit.auditlog.core.chain.Sha256AuditChain;
import io.github.pkyit.auditlog.core.mask.RuleBasedAuditMasker;
import io.github.pkyit.auditlog.core.metrics.SimpleAuditMetrics;
import io.github.pkyit.auditlog.core.pipeline.AuditPipeline;
import io.github.pkyit.auditlog.core.serialize.JacksonAuditSerializer;
import io.github.pkyit.auditlog.core.serialize.JsonLinesAuditCodec;
import io.github.pkyit.auditlog.core.store.JsonFileAuditStore;
import io.github.pkyit.auditlog.core.store.LoggingAuditStore;
import io.github.pkyit.auditlog.core.wal.FileAuditJournal;
import io.github.pkyit.auditlog.spring.actuator.AuditLogHealthIndicator;
import io.github.pkyit.auditlog.spring.actuator.AuditLogManagementEndpoint;
import io.github.pkyit.auditlog.spring.actuator.AuditLogMetricsEndpoint;
import io.github.pkyit.auditlog.spring.collect.AuditCaptureService;
import io.github.pkyit.auditlog.spring.identity.AnonymousPrincipalResolver;
import io.github.pkyit.auditlog.spring.identity.DefaultClientIpResolver;
import io.github.pkyit.auditlog.spring.identity.HeaderPrincipalResolver;
import io.github.pkyit.auditlog.spring.identity.HeaderTenantResolver;
import io.github.pkyit.auditlog.spring.identity.HeaderTraceIdResolver;
import io.github.pkyit.auditlog.spring.identity.SecurityContextPrincipalResolver;
import io.github.pkyit.auditlog.spring.method.AuditLogWebMvcRegistrations;
import io.github.pkyit.auditlog.spring.method.AuditMethodRegistry;
import io.github.pkyit.auditlog.spring.pipeline.AuditPipelineLifecycle;
import io.github.pkyit.auditlog.spring.properties.AuditLogProperties;
import io.github.pkyit.auditlog.spring.servlet.AuditLogServletFilter;
import io.github.pkyit.auditlog.spring.store.JdbcAuditStore;
import io.github.pkyit.auditlog.spring.validation.AuditLogStartupValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 审计框架自动配置，是可插拔能力的装配入口。
 *
 * <p>装配约定：
 * <ul>
 *   <li>通过 {@code AutoConfiguration.imports} 注册，引入依赖即生效，无需任何注解；</li>
 *   <li>所有默认实现均用 {@code @ConditionalOnMissingBean} 包裹：使用者声明同类型 Bean 即接管；</li>
 *   <li>所有能力均可独立开关（边界通道 / 语义通道 / 存储类型 / 哈希链 / WAL / Actuator）；</li>
 *   <li>不依赖宿主是否加了 {@code @EnableAsync}：异步由框架自带的消费线程保证；</li>
 *   <li>内部组件一律按强类型注入：按名称 {@code getBean} 加强制转型会在对应能力被关闭时
 *       因容器中不存在该 Bean 而启动失败。</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(AuditLogProperties.class)
@ConditionalOnProperty(prefix = AuditLogProperties.PREFIX, name = "enabled", havingValue = "true",
	matchIfMissing = true)
public class AuditLogAutoConfiguration {

	private static final Logger log = LoggerFactory.getLogger(AuditLogAutoConfiguration.class);

	// ------------------------------------------------------------------
	// 基础设施
	// ------------------------------------------------------------------

	@Bean
	@ConditionalOnMissingBean
	public AuditMetrics auditMetrics() {
		// 默认使用无外部依赖的指标实现。若宿主需要把审计指标汇入 Prometheus 等系统，
		// 只需提供一个自定义 AuditMetrics Bean，整个框架（含指标端点）会自动采用。
		return new SimpleAuditMetrics();
	}

	@Bean
	@ConditionalOnMissingBean
	public AuditFailureHandler auditFailureHandler() {
		return AuditFailureHandler.logging();
	}

	@Bean
	@ConditionalOnMissingBean
	public AuditCodec auditCodec() {
		return new JsonLinesAuditCodec();
	}

	@Bean
	@ConditionalOnMissingBean
	public ObjectMapper auditObjectMapper() {
		// 审计快照必须格式稳定，因此使用独立 ObjectMapper，不复用宿主的那一个：
		// 宿主的命名策略、日期格式、字段过滤器会让审计格式随应用配置漂移。
		return JacksonAuditSerializer.defaultMapper();
	}

	@Bean
	@ConditionalOnMissingBean
	public AuditSerializer auditSerializer(ObjectMapper auditObjectMapper) {
		return new JacksonAuditSerializer(auditObjectMapper);
	}

	@Bean
	@ConditionalOnMissingBean
	public AuditMasker auditMasker(ObjectMapper auditObjectMapper, AuditMetrics auditMetrics,
			AuditLogProperties properties) {
		boolean customMasking = properties.getMasking().isEnabled();
		if (!customMasking) {
			// 该告警必须与真实行为逐条对应：masking.enabled=false 只关闭字段规则与内容级正则，
			// 凭据类请求头的强制打码不受该开关影响。日志与行为一旦不一致，
			// 使用者会据此得出错误的合规结论。
			log.warn("[audit-log] 自定义脱敏已显式关闭（audit.log.masking.enabled=false）："
				+ "字段规则与内容正则将不再执行，请求载荷/查询串按原文记录。"
				+ "请注意：凭据类请求头（Authorization/Cookie/API-Key 等）仍会被强制打码 —— "
				+ "该项不可关闭。请确认这符合你的合规要求");
		}
		// 内容级规则与字段规则一并传入：脱敏器会在启动时把它们合并为单个预筛正则。
		// 逐值分别匹配 N 条正则在采集路径上会产生可观的 CPU 开销。
		return new RuleBasedAuditMasker(auditObjectMapper, auditMetrics,
			properties.getMasking().toFieldRules(), properties.getMasking().toRegexRules(), customMasking);
	}

	// ------------------------------------------------------------------
	// 防篡改链
	// ------------------------------------------------------------------

	@Bean
	@ConditionalOnMissingBean
	public AuditChain auditChain(AuditLogProperties properties) {
		if (!properties.getChain().isEnabled()) {
			log.info("[audit-log] 哈希链未启用（audit.log.chain.enabled=false）："
				+ "审计记录缺少防篡改能力，生产合规场景建议开启并配置 secret");
			return Sha256AuditChain.noop();
		}
		String secret = properties.getChain().getSecret();
		if (secret == null || secret.isBlank()) {
			log.warn("[audit-log] 哈希链已启用但未配置 secret，使用无密钥 SHA-256 链。"
				+ "该模式能发现意外损坏，但无法抵御拥有写权限的攻击者（对方可重算整条链）；"
				+ "生产环境请配置 audit.log.chain.secret");
			return new Sha256AuditChain();
		}
		return new HmacAuditChain(secret);
	}

	// ------------------------------------------------------------------
	// 存储
	// ------------------------------------------------------------------

	@Bean
	@ConditionalOnMissingBean
	public AuditStore auditStore(AuditLogProperties properties, AuditCodec codec, AuditMetrics metrics,
			ObjectProvider<DataSource> dataSourceProvider) {
		String type = properties.getStore().getType() == null ? "logging"
			: properties.getStore().getType().trim().toLowerCase(Locale.ROOT);
		return switch (type) {
			case "json-file", "jsonfile", "file" -> {
				JsonFileAuditStore store = new JsonFileAuditStore(
					Path.of(properties.getStore().getDirectory()), codec, metrics,
					properties.getStore().getMaxFileSize(), properties.getStore().getMaxHistory(),
					properties.getStore().getFilePrefix());
				try {
					store.open();
				}
				catch (Exception error) {
					throw new IllegalStateException(
						"审计文件存储无法初始化：" + properties.getStore().getDirectory(), error);
				}
				yield store;
			}
			case "jdbc" -> {
				DataSource dataSource = dataSourceProvider.getIfAvailable();
				if (dataSource == null) {
					throw new IllegalStateException("audit.log.store.type=jdbc 需要容器中存在 DataSource"
						+ "（请引入 spring-boot-starter-jdbc 并配置数据源）");
				}
				JdbcAuditStore store = new JdbcAuditStore(dataSource, properties.getStore().getTableName(),
					codec, metrics);
				if (properties.getStore().isAutoCreateTable()) {
					store.createTableIfAbsent();
				}
				yield store;
			}
			default -> new LoggingAuditStore(metrics);
		};
	}

	// ------------------------------------------------------------------
	// 本地兜底日志（WAL）
	// ------------------------------------------------------------------

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = AuditLogProperties.PREFIX + ".journal", name = "enabled",
		havingValue = "true", matchIfMissing = true)
	public AuditJournal auditJournal(AuditLogProperties properties, AuditCodec codec, AuditMetrics metrics) {
		return new FileAuditJournal(
			Path.of(properties.getJournal().getDirectory()), codec, metrics,
			properties.getJournal().getMaxFileSize(), properties.getJournal().getMaxHistory(),
			properties.getJournal().getQueueCapacity(), properties.getJournal().getMemoryBufferLimit());
	}

	// ------------------------------------------------------------------
	// 流水线（有界队列 + 背压 + 重试 + 死信）
	// ------------------------------------------------------------------

	@Bean
	@ConditionalOnMissingBean
	public io.github.pkyit.auditlog.core.api.AuditChainStore auditChainStore(AuditLogProperties properties) {
		// 链状态文件与链开关无关地创建：这样"先采集、后开启链"的场景也能立刻开始持久化，
		// 否则开启链之前产生的记录会成为无法衔接的历史空洞。
		return new io.github.pkyit.auditlog.core.chain.FileChainStateStore(
			java.nio.file.Paths.get(properties.getChain().getStateFile()));
	}

	@Bean
	@ConditionalOnMissingBean
	public AuditPipeline auditPipeline(AuditLogProperties properties, AuditStore auditStore, AuditChain auditChain,
			AuditMetrics auditMetrics, AuditFailureHandler failureHandler,
			ObjectProvider<AuditJournal> journalProvider,
			ObjectProvider<io.github.pkyit.auditlog.core.api.AuditChainStore> chainStoreProvider) {
		AuditPipeline.Config config = new AuditPipeline.Config(
			properties.getPipeline().getQueueCapacity(),
			properties.getPipeline().getBatchSize(),
			properties.getPipeline().getBatchTimeoutMillis(),
			properties.getPipeline().getConsumerThreads(),
			properties.getPipeline().getStrategy(),
			properties.getPipeline().getBlockTimeoutMillis(),
			properties.getPipeline().getMaxRetries(),
			properties.getPipeline().getRetryBackoffMillis(),
			properties.getChain().isEnabled(),
			properties.getPipeline().getRecentCacheSize());
		return new AuditPipeline(config, auditStore, auditChain, auditMetrics, failureHandler,
			journalProvider.getIfAvailable(), chainStoreProvider.getIfAvailable());
	}

	@Bean
	@ConditionalOnMissingBean
	public AuditPipelineLifecycle auditPipelineLifecycle(AuditPipeline pipeline, AuditLogProperties properties) {
		return new AuditPipelineLifecycle(pipeline, properties);
	}

	// ------------------------------------------------------------------
	// 身份与来源解析
	// ------------------------------------------------------------------

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
	public static class SecurityPrincipalConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public SecurityContextPrincipalResolver securityContextPrincipalResolver() {
			return new SecurityContextPrincipalResolver();
		}
	}

	@Bean
	@ConditionalOnMissingBean
	public ClientIpResolver clientIpResolver(AuditLogProperties properties) {
		if (properties.getIdentity().isTrustProxyHeaders() && properties.getIdentity().getTrustedProxies().isEmpty()) {
			log.error("[audit-log] 已开启 trust-proxy-headers 但未配置 trusted-proxies："
				+ "任何客户端都能通过伪造 X-Forwarded-For 篡改审计记录中的来源地址，"
				+ "因此代理头将被忽略，仅使用 TCP 层地址。请配置 audit.log.identity.trusted-proxies");
		}
		return new DefaultClientIpResolver(properties.getIdentity().isTrustProxyHeaders()
			? properties.getIdentity().getTrustedProxies()
			: List.of());
	}

	@Bean
	@ConditionalOnMissingBean
	public TenantResolver tenantResolver(AuditLogProperties properties) {
		return new HeaderTenantResolver(properties.getIdentity().getTenantIdHeader());
	}

	@Bean
	@ConditionalOnMissingBean
	public TraceIdResolver traceIdResolver(AuditLogProperties properties) {
		return new HeaderTraceIdResolver(properties.getIdentity().getTraceIdHeader(),
			properties.getIdentity().getFallbackTraceIdHeader());
	}

	/**
	 * 构建主体解析器链。
	 *
	 * <p>默认顺序：认证上下文（可信）→ 匿名兜底。仅当显式开启
	 * {@code header-principal-enabled} 时插入请求头解析器，
	 * 且其产物被标记为 {@code TRUSTED_HEADER}，审计消费方可据此判断可信度。
	 *
	 * <p>此处不注册 {@code List<PrincipalResolver>} 类型的 Bean：一旦使用者提供自定义解析器，
	 * List 与单个 Bean 会同时存在并产生注入歧义。解析器链改为在
	 * {@code auditCaptureService} 中就地构建。
	 */
	private static List<PrincipalResolver> resolvePrincipalChain(AuditLogProperties properties,
			ObjectProvider<SecurityContextPrincipalResolver> securityResolverProvider,
			ObjectProvider<PrincipalResolver> customResolvers) {
		return AuditCaptureService.buildResolverChain(
			customResolvers.orderedStream().toList(),
			securityResolverProvider.getIfAvailable(),
			properties);
	}

	// ------------------------------------------------------------------
	// 采集
	// ------------------------------------------------------------------

	// ------------------------------------------------------------------
	// 采集通道：按 Web 环境分派
	//
	// 自动配置的注册粒度必须与 Web 环境绑定：
	//   `AuditMethodRegistry` / `AuditCaptureService` 的类签名引用了
	//   `RequestMappingHandlerMapping`（servlet 专属），若把它们注册为顶层 bean，
	//   Spring 内省 bean 定义时就要解析这些类，在 classpath 上没有 servlet 类的
	//   WebFlux 环境下会抛 NoClassDefFoundError 导致启动失败。
	//   `@ConditionalOnClass` 无法保护「在其他位置直接引用该类型」的 bean 方法。
	//
	// 因此采集相关的 bean 一律放进带环境条件的嵌套配置里。
	// ------------------------------------------------------------------

	/**
	 * 非 Servlet 环境下的默认空注册表。
	 *
	 * <p>Actuator 端点与（Servlet 侧的）启动校验都会注入 {@code AuditMethodRegistry}。
	 * WebFlux 环境下不存在 Servlet 版注册表，缺少本默认实现时这些 bean 会因依赖缺失而创建失败。
	 *
	 * <p>必须用 {@code @ConditionalOnMissingClass} 而不是 {@code @ConditionalOnMissingBean}：
	 * 后者会与 Servlet 侧的具体注册表竞争，本 bean 一旦先注册，
	 * Servlet 侧的 {@code @ConditionalOnMissingBean} 就会抑制掉真正的实现，
	 * 使 Servlet 环境下注册表退化为空、方法级元数据全部丢失。
	 * 以「classpath 上是否存在 servlet」分流，二者不会同时生效，因而不存在竞争。
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingClass("jakarta.servlet.Filter")
	public static class NonServletRegistryConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public AuditMethodRegistry auditMethodRegistry() {
			return AuditMethodRegistry.empty();
		}
	}

	/**
	 * Web 环境无关的采集编排（Servlet 与 Reactive 共用同一份）。
	 *
	 * <p>{@code AuditCaptureService} 只依赖 {@code AuditRequestView} / {@code AuditResponseView}，
	 * 不依赖任何 Web 类型，因此两种环境共用同一份脱敏、截断、主体解析与结果判定逻辑，
	 * 审计语义不随容器类型变化而漂移。
	 *
	 * <p>方法级元数据注册表与环境相关，由各自的环境配置提供：
	 * Servlet 侧给出可扫描实现，Reactive 侧给出请求期解析实现。
	 * 此处用 {@code @ConditionalOnWebApplication} 加对应 {@code @ConditionalOnClass}
	 * 与各环境配置互斥，避免 {@code @ConditionalOnMissingBean} 造成的相互抑制。
	 */
	@Configuration(proxyBeanMethods = false)
	// 不限定 type：默认即 ANY。Servlet / Reactive 都需要采集编排，
	// 而"是哪种环境"由各自的适配配置去决定。
	@ConditionalOnWebApplication
	public static class WebCaptureConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public AuditCaptureService auditCaptureService(AuditLogProperties properties,
				ObjectProvider<AuditMethodRegistry> registryProvider,
				ObjectProvider<PrincipalResolver> principalResolversProvider,
				ObjectProvider<SecurityContextPrincipalResolver> securityResolverProvider,
				ObjectProvider<ClientIpResolver> clientIpResolverProvider,
				ObjectProvider<TenantResolver> tenantResolverProvider,
				ObjectProvider<TraceIdResolver> traceIdResolverProvider, AuditMasker masker,
				AuditSerializer serializer, AuditPipeline pipeline, AuditMetrics metrics,
				AuditFailureHandler failureHandler) {
			List<PrincipalResolver> resolvers = resolvePrincipalChain(properties, securityResolverProvider,
				principalResolversProvider);
			return new AuditCaptureService(properties, registryProvider.getIfAvailable(),
				resolvers,
				clientIpResolverProvider.getIfAvailable(),
				tenantResolverProvider.getIfAvailable(),
				traceIdResolverProvider.getIfAvailable(),
				masker, serializer, pipeline, metrics, failureHandler);
		}
	}

	/**
	 * Reactive（WebFlux）环境的边界通道、语义通道与注册表。
	 *
	 * <ul>
	 *   <li>{@code WebFilter} 以 Bean 形式注册即可，不需要 Servlet 的 FilterRegistrationBean；</li>
	 *   <li>顺序取 {@code -150}（早于 Spring Security 反应式链的 -100），
	 *       使 401/403 也被记录，与 Servlet 侧策略一致；</li>
	 *   <li>注册表不做启动全量扫描：反应式下元数据在请求期从 exchange 取已匹配的
	 *       {@code HandlerMethod}。</li>
	 * </ul>
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = "org.springframework.web.server.WebFilter")
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
	@ConditionalOnProperty(prefix = AuditLogProperties.PREFIX + ".collector.boundary", name = "enabled",
		havingValue = "true", matchIfMissing = true)
	public static class ReactiveBoundaryConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public AuditMethodRegistry auditMethodRegistry() {
			// 请求期解析注解。不可使用 AuditMethodRegistry.empty()：
			// AuditCaptureService 经注册表读取元数据，空注册表会使语义通道静默失效，
			// 表现为 operation=null、actionType=UNKNOWN。
			return new io.github.pkyit.auditlog.spring.method.ResolvingAuditMethodRegistry();
		}

		@Bean
		@ConditionalOnMissingBean
		public io.github.pkyit.auditlog.spring.reactive.AuditLogWebFilter auditLogWebFilter(
				AuditCaptureService captureService, AuditLogProperties properties) {
			return new io.github.pkyit.auditlog.spring.reactive.AuditLogWebFilter(captureService, properties);
		}

		/**
		 * Reactive 语义通道装配。
		 *
		 * <p>用 {@code @Import} 引入独立配置类而不是在此直接声明 Bean：
		 * 那个配置类引用了 WebFlux 专属类型，独立成类后可以被
		 * {@code @ConditionalOnClass} 用 ASM 安全地挡在 Servlet 环境之外。
		 */
		@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
		@ConditionalOnClass(name = "org.springframework.web.reactive.HandlerAdapter")
		@ConditionalOnProperty(prefix = AuditLogProperties.PREFIX + ".collector.method", name = "enabled",
			havingValue = "true", matchIfMissing = true)
		@org.springframework.context.annotation.Import(
			io.github.pkyit.auditlog.spring.reactive.ReactiveSemanticChannelConfiguration.class)
		public static class ReactiveSemanticChannelImportConfiguration {
		}

		/**
		 * 启动自检：确认审计过滤器已注册到 WebFlux 链上。
		 *
		 * <p>反应式环境下最危险的失败模式是应用正常启动、但一条审计都不采集（静默零采集），
		 * 而非显式报错。因此必须在容器就绪后核对 {@code WebFilter} Bean 是否存在，
		 * 缺失时明确告警，不得静默跳过。
		 */
		@Bean
		public org.springframework.boot.ApplicationRunner auditReactiveSelfCheck(
				AuditLogProperties properties,
				org.springframework.beans.factory.ObjectProvider<
					io.github.pkyit.auditlog.spring.reactive.AuditLogWebFilter> filterProvider) {
			return args -> {
				boolean present = filterProvider.getIfAvailable() != null;
				if (present) {
					log.info("[audit-log] 审计运行环境 = REACTIVE（WebFlux）；"
						+ "边界过滤器已注册，order={}", properties.getCollector().getBoundary().getOrder());
				}
				else {
					log.error("[audit-log] 审计运行环境 = REACTIVE（WebFlux），"
						+ "但**未注册任何审计 WebFilter**：审计将静默零采集！"
						+ "请检查 audit.log.collector.boundary.enabled 与 WebFilter 相关自动配置是否被排除");
				}
			};
		}
	}

	/** 边界通道（Servlet Filter）：保证「认证失败 / 403 / 404 / 绑定失败」也被记录。 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = "jakarta.servlet.Filter")
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
	@ConditionalOnProperty(prefix = AuditLogProperties.PREFIX + ".collector.boundary", name = "enabled",
		havingValue = "true", matchIfMissing = true)
	public static class ServletBoundaryConfiguration {

		/**
		 * 方法级元数据注册表（Servlet 版）。
		 *
		 * <p>此处只创建空注册表，真实填充由 {@code AuditLogStartupValidator} 在
		 * 容器刷新完成后执行（见 {@link io.github.pkyit.auditlog.spring.method.ServletMethodRegistry#populate}）。
		 * 若在此处直接注入 {@code RequestMappingHandlerMapping}，会提前触发 WebMvc 自动配置，
		 * 而它依赖本框架的 {@code WebMvcRegistrations}，最终形成
		 * {@code 注册表 → EnableWebMvcConfiguration → WebMvcRegistrations → 采集服务 → 注册表}
		 * 的循环依赖，导致应用启动失败。
		 *
		 * <p>返回类型为具体类型 {@code ServletMethodRegistry}，因此按类型注入
		 * {@code AuditMethodRegistry} 时它是唯一候选（非 Servlet 环境的默认空实现
		 * 被 {@code @ConditionalOnMissingClass} 排除，二者不会共存）。
		 */
		@Bean
		@ConditionalOnMissingBean
		public io.github.pkyit.auditlog.spring.method.ServletMethodRegistry servletMethodRegistry() {
			return io.github.pkyit.auditlog.spring.method.ServletMethodRegistry.empty();
		}

		@Bean
		@ConditionalOnMissingBean
		public AuditLogServletFilter auditLogServletFilter(AuditCaptureService captureService,
				AuditLogProperties properties) {
			return new AuditLogServletFilter(captureService, properties);
		}

		/**
		 * 过滤器注册。
		 *
		 * <p>默认顺序早于 Spring Security 的过滤器区间，
		 * 使 401/403 响应同样经由审计过滤器的 {@code finally} 进入审计记录。
		 */
		@Bean
		@ConditionalOnMissingBean(name = "auditLogFilterRegistration")
		public org.springframework.boot.web.servlet.FilterRegistrationBean<AuditLogServletFilter> auditLogFilterRegistration(
				AuditLogServletFilter filter, AuditLogProperties properties) {
			org.springframework.boot.web.servlet.FilterRegistrationBean<AuditLogServletFilter> registration =
				new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
			registration.setOrder(properties.getCollector().getBoundary().getOrder());
			registration.setName("auditLogFilter");
			registration.addUrlPatterns("/*");
			return registration;
		}
	}

	/** 语义通道（HandlerAdapter 扩展，可关闭）。 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter")
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
	@ConditionalOnProperty(prefix = AuditLogProperties.PREFIX + ".collector.method", name = "enabled",
		havingValue = "true", matchIfMissing = true)
	public static class MethodCollectorConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public AuditLogWebMvcRegistrations auditLogWebMvcRegistrations(AuditCaptureService captureService) {
			return new AuditLogWebMvcRegistrations(captureService);
		}
	}

	// ------------------------------------------------------------------
	// 启动校验（防止「静默失效」）
	//
	// 放在环境条件下：AuditLogStartupValidator 引用了 Servlet 专属的
	// RequestMappingHandlerMapping，若在 WebFlux 环境下注册会引发 NoClassDefFoundError。
	// ------------------------------------------------------------------

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping")
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
	public static class StartupValidationConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public AuditLogStartupValidator auditLogStartupValidator(AuditLogProperties properties,
				AuditMethodRegistry registry, AuditStore store, AuditChain chain, AuditMetrics metrics,
				ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider,
				org.springframework.beans.factory.ListableBeanFactory beanFactory,
				ObjectProvider<AuditLogAnnotationProperties> annotationPropertiesProvider) {
			return new AuditLogStartupValidator(properties, registry, store, chain, metrics,
				handlerMappingProvider, beanFactory, annotationPropertiesProvider);
		}
	}

	// ------------------------------------------------------------------
	// Actuator 集成（可选）
	// ------------------------------------------------------------------

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
	public static class ActuatorConfiguration {

		@Bean
		@ConditionalOnMissingBean
		@ConditionalOnProperty(prefix = AuditLogProperties.PREFIX + ".actuator", name = "health-enabled",
			havingValue = "true", matchIfMissing = true)
		public AuditLogHealthIndicator auditLogHealthIndicator(AuditPipeline pipeline,
				ObjectProvider<AuditJournal> journalProvider, AuditStore store, AuditLogProperties properties) {
			return new AuditLogHealthIndicator(pipeline, journalProvider.getIfAvailable(), store, properties);
		}

		@Bean
		@ConditionalOnMissingBean
		@ConditionalOnProperty(prefix = AuditLogProperties.PREFIX + ".actuator", name = "management-endpoint-enabled",
			havingValue = "true", matchIfMissing = true)
		public AuditLogManagementEndpoint auditLogManagementEndpoint(AuditMethodRegistry registry,
				AuditPipeline pipeline, AuditLogProperties properties) {
			return new AuditLogManagementEndpoint(registry, pipeline, properties);
		}
	}

	/**
	 * 指标端点。
	 *
	 * <p>不强制依赖 Micrometer：若宿主需要把审计指标汇入 Prometheus 等系统，
	 * 提供自定义 {@link AuditMetrics} 实现即可，本端点与整个框架会自动采用。
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
	@ConditionalOnProperty(prefix = AuditLogProperties.PREFIX + ".actuator", name = "metrics-endpoint-enabled",
		havingValue = "true", matchIfMissing = true)
	public AuditLogMetricsEndpoint auditLogMetricsEndpoint(AuditMetrics metrics) {
		return new AuditLogMetricsEndpoint(metrics);
	}
}
