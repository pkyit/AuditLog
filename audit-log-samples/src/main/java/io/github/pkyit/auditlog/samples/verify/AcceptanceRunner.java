package io.github.pkyit.auditlog.samples.verify;

import io.github.pkyit.auditlog.core.api.AuditStore;
import io.github.pkyit.auditlog.core.pipeline.AuditPipeline;
import io.github.pkyit.auditlog.samples.repo.DemoRecordRepository;
import io.github.pkyit.auditlog.spring.method.AuditMethodRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 端到端验收入口。
 *
 * <p>在 {@code demo} profile 下运行：应用启动完成后，对本机真实端口发起 HTTP 请求，
 * 断言审计产物，最后打印报告并以退出码反映结果（失败为 1），可直接用于 CI。
 *
 * <p>验收以应用内监听器而非单元测试实现：构建环境离线且本地仓库缺少 surefire 的
 * JUnit provider，{@code mvn test} 不可用。该实现不经 Mock，覆盖真实 HTTP 链路、
 * 真实 Spring MVC 扩展点、真实事务与真实存储降级路径。
 */
@Component
@Profile("demo")
@Order(Ordered.LOWEST_PRECEDENCE)
public class AcceptanceRunner implements ApplicationListener<ApplicationReadyEvent> {

	private static final Logger log = LoggerFactory.getLogger(AcceptanceRunner.class);

	/**
	 * 应用是否在验收完成后退出。
	 *
	 * <p>由系统属性 {@code audit.demo.exit} 控制，取值为 {@code false}（忽略大小写）时保持进程存活，
	 * 便于人工继续观察应用；其余取值（含缺省）为退出，用于 CI。
	 */
	private final boolean exitAfterRun = !"false".equalsIgnoreCase(System.getProperty("audit.demo.exit", "true"));

	/**
	 * 应用就绪后执行全部验收检查，输出报告，并按需以退出码结束进程。
	 *
	 * @param event 应用就绪事件；其 {@code ApplicationContext} 必须能提供审计存储、流水线、
	 * 		方法注册表、示例仓储与指标实现
	 */
	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		ConfigurableApplicationContext context = event.getApplicationContext();
		int port = resolvePort(context);
		String baseUrl = "http://127.0.0.1:" + port;

		AuditStore store = context.getBean(AuditStore.class);
		AuditPipeline pipeline = context.getBean(AuditPipeline.class);
		AuditMethodRegistry registry = context.getBean(AuditMethodRegistry.class);
		DemoRecordRepository repository = context.getBean(DemoRecordRepository.class);
		io.github.pkyit.auditlog.core.api.AuditMetrics metrics =
			context.getBean(io.github.pkyit.auditlog.core.api.AuditMetrics.class);

		AuditAcceptanceVerifier verifier = new AuditAcceptanceVerifier(baseUrl, store, pipeline, registry,
			repository, metrics);

		boolean passed;
		try {
			passed = verifier.runAll();
		}
		catch (Throwable error) {
			log.error("[audit-acceptance] 验收执行异常", error);
			passed = false;
		}

		String report = verifier.report();
		// 同时输出到日志与标准输出，便于在两种运行方式下都能看到结果
		log.info("\n{}", report);
		System.out.println(report);

		if (exitAfterRun) {
			int exitCode = passed ? 0 : 1;
			System.out.println("[audit-acceptance] 退出码 = " + exitCode
				+ "（通过 " + verifier.passedCount() + " 项，失败 " + verifier.failedCount() + " 项）");
			// 优雅关闭：触发 SmartLifecycle#stop，排空审计队列并 flush 兜底日志
			int code = exitCode;
			new Thread(() -> {
				try {
					Thread.sleep(500L);
				}
				catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
				System.exit(code);
			}, "audit-acceptance-exit").start();
		}
	}

	/**
	 * 解析 HTTP 验收要访问的本机端口。
	 *
	 * <p>{@code server.port=0} 表示由系统分配端口，此时配置值不是真实监听端口，必须回退到
	 * {@code WebServer} 实际绑定的端口。
	 *
	 * @param context 已就绪的应用上下文
	 * @return 本机 HTTP 监听端口
	 * @throws IllegalStateException 环境未配置 {@code server.port}，且上下文中不存在 WebServer
	 */
	private int resolvePort(ConfigurableApplicationContext context) {
		Object port = context.getEnvironment().getProperty("server.port");
		if (port != null) {
			try {
				return Integer.parseInt(String.valueOf(port));
			}
			catch (NumberFormatException ignored) {
				// 回退到 web server 初始化事件记录的端口
			}
		}
		Object actual = context.getBeanProvider(org.springframework.boot.web.context.WebServerApplicationContext.class)
			.getIfAvailable();
		if (actual instanceof org.springframework.boot.web.context.WebServerApplicationContext webContext) {
			return webContext.getWebServer().getPort();
		}
		throw new IllegalStateException("无法确定服务端口");
	}
}
