package io.github.pkyit.auditlog.spring.collect;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务与审计记录的对齐。
 *
 * <p>审计记录只有在业务事务结束后才能确定结果：若在事务提交前发布事件，业务回滚时记录
 * 仍是「成功」，形成无法事后识别的假阳性。本类在事务同步激活时注册回调，把事务的最终结果
 * （提交 / 回滚）写回审计上下文，使记录能表达操作是否真的生效：
 * <ul>
 *   <li>提交 → {@code transactional=true, rolledBack=false}；</li>
 *   <li>回滚 → {@code transactional=true, rolledBack=true}，并按配置决定
 *       {@code after-rollback=DROP}（默认，不产生假阳性）还是 {@code RECORD}；</li>
 *   <li>无事务 → 保持默认值，不虚报事务语义。</li>
 * </ul>
 *
 * <p>Spring 的 {@code afterCompletion} 发生在业务方法返回之后、Servlet 过滤器
 * {@code finally} 之前，该顺序是 {@link AuditCaptureService#finish} 能读到事务结果的前提：
 * 回调若晚于 {@code finish} 执行，事务结果不会出现在记录中。
 */
public final class TransactionAuditTracker {

	private TransactionAuditTracker() {
	}

	/** 事务最终结果。 */
	public enum TransactionResult {
		/** 已提交。 */
		COMMITTED,
		/** 已回滚。 */
		ROLLED_BACK,
		/** 无事务。 */
		NONE
	}

	/**
	 * 在当前事务上注册审计同步回调。
	 *
	 * <p>仅在当前线程已激活事务同步时注册；未激活，或未引入 spring-tx、事务管理器不可用时，
	 * 不注册并保持上下文的「无事务」语义。本方法不抛异常，注册失败不外溢到业务。
	 *
	 * @param context 审计上下文；为 {@code null} 时直接返回
	 */
	public static void register(AuditRecordContext context) {
		if (context == null) {
			return;
		}
		try {
			if (!TransactionSynchronizationManager.isSynchronizationActive()) {
				return;
			}
			context.builder().transactional(true);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					if (status == STATUS_COMMITTED) {
						context.setTransactionResult(TransactionResult.COMMITTED);
					}
					else {
						context.setTransactionResult(TransactionResult.ROLLED_BACK);
						context.builder().rolledBack(true);
					}
				}
			});
		}
		catch (Throwable ignored) {
			// 未引入 spring-tx 或事务管理器不可用：保持"无事务"语义，不虚报
		}
	}
}
