package io.github.pkyit.examples.erdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ER 示例系统启动类。
 *
 * <p>本工程是被测框架的**外部消费者**：仅通过 Maven 中央仓库引入
 * {@code io.github.pkyit:audit-log-spring-boot-starter}，不依赖其源码或本地模块。
 * 自动配置会自行装配审计的边界通道与语义通道，此处不需要任何审计相关注解。
 */
@SpringBootApplication
public class ErDemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(ErDemoApplication.class, args);
	}
}
