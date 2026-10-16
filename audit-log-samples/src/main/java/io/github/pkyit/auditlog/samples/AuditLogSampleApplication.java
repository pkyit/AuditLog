package io.github.pkyit.auditlog.samples;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例应用：引入 starter 依赖即可获得审计能力（验收点 A1）。
 *
 * <p>启动类上没有审计相关注解，也没有 {@code @EnableAuditLog} 与 {@code @EnableAsync}：
 * 审计能力全部来自 starter 的自动配置（{@code META-INF/spring/...AutoConfiguration.imports}），
 * 因此任意 Spring Boot 项目只需加入依赖即可插拔使用。
 */
@SpringBootApplication
public class AuditLogSampleApplication {

	/**
	 * 启动示例应用，审计能力由 starter 自动配置装配。
	 *
	 * @param args 命令行参数，未使用
	 */
	public static void main(String[] args) {
		SpringApplication.run(AuditLogSampleApplication.class, args);
	}
}
