package io.github.pkyit.auditlog.core.api;

/**
 * 链状态的持久化 SPI。
 *
 * <p>必须在每批成功写入存储之后保存，且保存动作要尽量靠近写入点：
 * 落库与记录链状态这两步之间的窗口越小，崩溃恢复时把已投递记录当作待重放记录的概率越低。
 *
 * <p>实现要求：
 * <ul>
 *   <li>{@link #save} 应当是原子的（写临时文件后原子改名，或单条 upsert），
 *       否则崩溃可能留下半个状态文件，{@link #load} 将无法还原链头；</li>
 *   <li>{@link #load} 在没有历史状态时返回 {@code null}（冷启动）或
 *       {@link ChainState#pending()}，二者都被视为从零开始；</li>
 *   <li>状态文件缺失或损坏时，须能通过 {@link AuditStore#recoverChainState()}
 *       从存储回读最大 seq 恢复链头，不使链的连续性依赖单一文件。</li>
 * </ul>
 */
public interface AuditChainStore {

	/** 读取链状态；无历史时返回 {@code null}。 */
	ChainState load();

	/** 原子保存链状态。 */
	void save(ChainState state);

	/** 名称（用于启动日志与诊断）。 */
	default String getName() {
		return getClass().getSimpleName();
	}
}
