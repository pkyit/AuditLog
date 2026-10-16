package io.github.pkyit.auditlog.samples;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * 示例应用的数据源初始化。
 *
 * <p>不使用 Spring Boot 的 {@code spring.sql.init}：该机制会引入 DataSource 自动配置的
 * 额外依赖链，与示例保持最小依赖面的目标冲突。这里直接执行 {@code schema.sql} 建表。
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseInitializer {

	/**
	 * 应用启动后执行 {@code schema.sql} 建表。
	 *
	 * <p>脚本错误不中断启动，以兼容表已存在的重复启动场景。
	 *
	 * @param dataSource 执行建表脚本的目标数据源
	 * @return 启动后触发的初始化 runner
	 */
	@Bean
	public ApplicationRunner demoSchemaInitializer(DataSource dataSource) {
		return args -> {
			ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
			populator.addScript(new ClassPathResource("schema.sql"));
			populator.setContinueOnError(true);
			populator.execute(dataSource);
		};
	}
}
