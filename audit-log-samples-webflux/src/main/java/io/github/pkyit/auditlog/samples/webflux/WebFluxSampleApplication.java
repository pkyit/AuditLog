package io.github.pkyit.auditlog.samples.webflux;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * WebFlux 宿主示例：运行时为 WebFlux/Netty，用于约束 starter 的依赖边界。
 *
 * <p>若 starter 硬依赖 {@code spring-boot-starter-web}，Tomcat 会被一并引入，
 * Spring Boot 推导出的 {@code WebApplicationType} 即为 SERVLET，反应式应用退化为
 * Servlet 应用；即便推导结果为 REACTIVE，Servlet 过滤器也不参与执行，审计将静默零采集。
 *
 * <p>两种形态分别表现为"能启动但行为错误"与"启动失败"，因此 starter 不得对 Servlet 栈
 * 产生硬依赖，采集必须同时覆盖两类运行时：边界由 {@code AuditLogWebFilter} 承担，
 * 语义由 {@code ReactiveSemanticChannelProcessor} 承担。
 */
@SpringBootApplication
public class WebFluxSampleApplication {

	public static void main(String[] args) {
		SpringApplication.run(WebFluxSampleApplication.class, args);
	}
}
