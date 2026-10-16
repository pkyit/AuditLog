package io.github.pkyit.auditlog.spring.collect;

import io.github.pkyit.auditlog.core.model.AuditRecord;
import io.github.pkyit.auditlog.spring.method.AuditMethodMetadata;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次请求的审计记录上下文（两阶段填充）。
 *
 * <p>一条记录只创建一次，由两个通道分阶段写入同一个 {@link AuditRecord.Builder}，
 * 而不是「两条记录事后按 requestId 合并」：
 * <ul>
 *   <li>阶段一（边界通道，请求进入）：创建上下文与 {@link AuditRecord.Builder}，填充来源信息；</li>
 *   <li>阶段二（语义通道，方法调用）：往同一个 builder 里填操作名/动作类型/资源；</li>
 *   <li>阶段三（边界通道，请求结束）：填真实状态码/结果/异常，然后唯一一次发布并冻结。</li>
 * </ul>
 *
 * <p>合并式实现存在竞态：粗粒度记录可能覆盖语义通道写入的字段。分阶段写入单一 builder
 * 不存在该问题，代价是写入必须严格按上述顺序发生。
 *
 * <p>本类可变，由请求线程独占使用，字段无需同步；唯一例外是事务结果字段
 * （{@code transactionResult}），它由 Spring 事务同步回调写入，可能与读取处于不同线程，
 * 因此声明为 {@code volatile}。
 */
public class AuditRecordContext {

	private final AuditRecord.Builder builder = new AuditRecord.Builder();
	private final String environment;
	private final long startNanos;
	private final java.time.ZoneId zoneId;

	// 由事务同步回调写入、请求结束阶段读取，跨线程可见性依赖 volatile
	private volatile TransactionAuditTracker.TransactionResult transactionResult =
		TransactionAuditTracker.TransactionResult.NONE;

	private boolean semanticCaptureAttempted;
	private boolean published;
	private AuditMethodMetadata methodMetadata;

	/** 全局递增计数，用于生成组内唯一的记录 ID。 */
	private static final AtomicLong SEQUENCE = new AtomicLong(0L);

	/** 进程内实例标识（写入记录，便于多实例汇聚后区分来源）。 */
	private static final String INSTANCE_ID = java.util.UUID.randomUUID().toString().substring(0, 8);
	private static final java.util.Random RANDOM = new java.util.Random();

	/**
	 * 创建上下文，并捕获记录起始时间与系统时区。
	 *
	 * @param environment 采集环境标识（{@code servlet} / {@code reactive}）；
	 *                    为 {@code null} 时回退为 {@code unknown}
	 */
	public AuditRecordContext(String environment) {
		this.environment = environment == null ? "unknown" : environment;
		this.startNanos = System.nanoTime();
		this.zoneId = java.time.ZoneId.systemDefault();
	}

	/** 本记录共用的构建器：边界通道与语义通道写入的是同一个实例。 */
	public AuditRecord.Builder builder() {
		return builder;
	}

	/** 采集环境（{@code servlet} / {@code reactive}），用于启动与诊断时核对适配器是否装错。 */
	public String environment() {
		return environment;
	}

	/** 自上下文创建到当前时刻的毫秒数；基于 {@link System#nanoTime()}，不受系统时间调整影响。 */
	public long elapsedMillis() {
		return (System.nanoTime() - startNanos) / 1_000_000L;
	}

	/** 上下文创建时捕获的系统时区；运行期时区变更不影响已创建的上下文。 */
	public java.time.ZoneId zoneId() {
		return zoneId;
	}

	/** 进程内实例标识，用于多实例汇聚后区分记录来源。 */
	public String instanceId() {
		return INSTANCE_ID;
	}

	/**
	 * 生成按时间有序的记录 ID：毫秒时间戳 + 进程内递增计数 + 随机位。
	 *
	 * <p>同一毫秒内的并发调用由递增计数与随机位区分；跨进程的来源区分见
	 * {@link #instanceId()}。
	 */
	public static String nextId() {
		long millis = System.currentTimeMillis();
		long counter = SEQUENCE.incrementAndGet() & 0xFFFFFL;
		long random = RANDOM.nextInt(1 << 12);
		return Long.toString(millis, 36) + '-' + Long.toString(counter, 36) + '-' + Long.toString(random, 36);
	}

	/** 语义通道是否已被调用过；已调用不等于命中 {@code @AuditLog}。 */
	public boolean isSemanticCaptureAttempted() {
		return semanticCaptureAttempted;
	}

	/** 标记语义通道已尝试填充；由语义通道在查找注解元数据之前调用。 */
	public void markSemanticCaptureAttempted() {
		this.semanticCaptureAttempted = true;
	}

	/** 命中的方法级注解元数据；未命中或语义通道未运行时为 {@code null}。 */
	public AuditMethodMetadata getMethodMetadata() {
		return methodMetadata;
	}

	/** 写入命中的方法元数据；{@code null} 表示本次请求未命中 {@code @AuditLog}。 */
	public void setMethodMetadata(AuditMethodMetadata methodMetadata) {
		this.methodMetadata = methodMetadata;
	}

	/** 事务最终结果；无事务或事务尚未结束时为 {@link TransactionAuditTracker.TransactionResult#NONE}。 */
	public TransactionAuditTracker.TransactionResult getTransactionResult() {
		return transactionResult;
	}

	/** 写入事务最终结果；调用方为 Spring 事务同步回调，每次事务至多写入一次。 */
	public void setTransactionResult(TransactionAuditTracker.TransactionResult transactionResult) {
		this.transactionResult = transactionResult;
	}

	/** 记录是否已发布；已发布时重复调用 {@link AuditCaptureService#finish} 不会再产生记录。 */
	public boolean isPublished() {
		return published;
	}

	/** 构建不可变记录并标记已发布；{@link #markPublished()} 用于只需标记、不需返回值的场景。 */
	public AuditRecord freeze() {
		this.published = true;
		return builder.build();
	}

	/** 标记已发布；由 {@link AuditCaptureService#finish} 在向管道提交之前调用，用于拦截重复发布。 */
	public void markPublished() {
		this.published = true;
	}

	/** 记录响应信息（供调试与扩展）。环境无关：只用视图暴露的状态码。 */
	public void describeResponse(AuditResponseView response) {
		if (response != null && response.status() != AuditResponseView.UNKNOWN_STATUS) {
			builder().tag("responseStatus", String.valueOf(response.status()));
		}
	}
}
