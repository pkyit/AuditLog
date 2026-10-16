package io.github.pkyit.auditlog.core.api;

import io.github.pkyit.auditlog.core.model.AuditRecord;

import java.util.List;

/**
 * 审计存储 SPI —— 框架与外部系统的唯一边界。
 *
 * <p>实现约定：
 * <ul>
 *   <li>存储 MUST 为追加写语义，不得原地更新或删除已写入的记录（不变量 I4）；</li>
 *   <li>{@link #save(List)} 要么整批成功，要么抛异常让框架重试或降级；部分成功必须抛异常，
 *       否则框架无法判断是否需要 WAL 兜底，未写入部分将被静默丢弃；</li>
 *   <li>实现应当幂等：框架在重试或 WAL 重放时可能重复投递同一批记录，
 *       实现应以 {@link AuditRecord#getId()} 或 {@code seq} 作为去重键；</li>
 *   <li>实现 MUST NOT 吞掉异常，其 {@code getClass().getName()} 会被记录到失败指标中。</li>
 * </ul>
 */
public interface AuditStore {

	/**
	 * 批量写入。
	 *
	 * @param records 记录列表（同一批次内 {@code seq} 单调递增；空列表视为成功且无副作用）
	 * @throws Exception 任何失败（框架据此触发重试、WAL 降级与死信）
	 */
	void save(List<AuditRecord> records) throws Exception;

	/** 存储名称，用于指标与日志。 */
	default String getName() {
		return getClass().getSimpleName();
	}

	/**
	 * 是否声明支持与业务事务同事务写入。
	 *
	 * <p>这是存储实现自己的承诺，框架不代为担保：只有实现确实能在业务事务内完成写入
	 * （例如使用同一个 {@code DataSource} 且方法处于事务中）时才应返回 {@code true}。
	 * 默认 {@code false}，因为异步流水线与同事务写入在设计上互斥。
	 */
	default boolean supportsSameTransaction() {
		return false;
	}

	/** 是否已就绪（供健康检查使用）。 */
	default boolean isHealthy() {
		return true;
	}

	/**
	 * 兜底恢复链路状态：从已落库内容回读最后一条记录的 {@code seq} 与 {@code recordHash}。
	 *
	 * <p>链状态（链头）通常单独持久化，若该状态丢失且无从回读，链的连续性即随之中断；
	 * 记录本身已携带 {@code seq} 与 {@code recordHash}，因此可以从存储回读链尾恢复，
	 * 使恢复能力不依赖单一文件。
	 *
	 * <p>不支持回读的实现返回 {@code null} 即可（框架会按冷启动处理并告警）。
	 *
	 * @return 恢复出的链状态；存储为空或不支持时返回 {@code null}
	 */
	default ChainState recoverChainState() {
		return null;
	}
}
