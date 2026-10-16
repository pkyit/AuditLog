package io.github.pkyit.auditlog.samples;

import io.github.pkyit.auditlog.core.api.AuditMetrics;
import io.github.pkyit.auditlog.core.api.AuditStore;
import io.github.pkyit.auditlog.core.store.InMemoryAuditStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 端到端验收专用配置（profile: demo）。
 *
 * <p>把默认的 logging 存储替换为内存存储，使验收程序可以直接断言审计产物
 * （主体来源、结果、脱敏、哈希链），不需要解析日志文本。
 *
 * <p>该配置同时是存储可插拔机制的示例：提供一个 {@link AuditStore} Bean 即接管存储，
 * 不需要修改框架代码（验收项 A10）。
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoInMemoryStoreConfiguration {

	/**
	 * 以 {@code @Primary} 覆盖默认的 logging 存储，仅在 {@code demo} profile 生效。
	 *
	 * <p>内存存储不额外上报投递指标，投递计数由 {@code AuditPipeline} 统一负责（ADR-013）。
	 *
	 * @param metrics 指标 SPI，由自动配置提供
	 * @return 进程内审计存储；进程退出即丢失全部记录
	 */
	@Bean
	@Primary
	public AuditStore demoInMemoryAuditStore(AuditMetrics metrics) {
		return new InMemoryAuditStore(metrics);
	}
}
