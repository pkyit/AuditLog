package io.github.pkyit.auditlog.spring.pipeline;

import io.github.pkyit.auditlog.core.pipeline.AuditPipeline;
import io.github.pkyit.auditlog.spring.properties.AuditLogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 审计流水线的生命周期绑定。
 *
 * <p>停机排空必须由 {@link SmartLifecycle} 回调驱动，不能依赖 Bean 销毁顺序：
 * 容器关闭时须先排空队列并 flush WAL，之后存储与数据源才可关闭；
 * Bean 销毁顺序不可预期，依赖它会让已入队但未投递的记录丢失。
 */
public class AuditPipelineLifecycle implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(AuditPipelineLifecycle.class);

	private final AuditPipeline pipeline;
	private final AuditLogProperties properties;
	private volatile boolean running;

	public AuditPipelineLifecycle(AuditPipeline pipeline, AuditLogProperties properties) {
		this.pipeline = pipeline;
		this.properties = properties;
	}

	@Override
	public void start() {
		pipeline.start();
		this.running = true;
	}

	@Override
	public void stop() {
		stop(() -> {
		});
	}

	@Override
	public void stop(Runnable callback) {
		try {
			pipeline.stop();
		}
		catch (Throwable error) {
			log.error("[audit-log] 审计流水线停止时发生异常，可能有记录未投递", error);
		}
		finally {
			this.running = false;
			callback.run();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	/**
	 * 启动阶段：尽量靠后（等 Web 容器与存储就绪）。
	 * 停止阶段：尽量靠前（先排空审计，再让存储/数据源关闭）。
	 */
	@Override
	public int getPhase() {
		return Integer.MAX_VALUE - 1024;
	}

	@Override
	public boolean isAutoStartup() {
		return properties.isEnabled();
	}
}
