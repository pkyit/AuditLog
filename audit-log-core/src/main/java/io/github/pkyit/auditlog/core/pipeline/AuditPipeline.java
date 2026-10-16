package io.github.pkyit.auditlog.core.pipeline;

import io.github.pkyit.auditlog.core.api.AuditChain;
import io.github.pkyit.auditlog.core.api.AuditFailureHandler;
import io.github.pkyit.auditlog.core.api.AuditJournal;
import io.github.pkyit.auditlog.core.api.AuditMetricNames;
import io.github.pkyit.auditlog.core.api.AuditMetrics;
import io.github.pkyit.auditlog.core.api.AuditStore;
import io.github.pkyit.auditlog.core.model.AuditRecord;
import io.github.pkyit.auditlog.core.wal.FileAuditJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计发布流水线 —— 框架的可靠性与背压核心。
 *
 * <p>提交与投递在这一层解耦，异步语义由框架自带的消费线程保证，不依赖宿主环境的
 * {@code @EnableAsync} 等异步装配。核心行为：
 * <ul>
 *   <li><b>有界队列 + 显式背压</b>：队列容量固定，满了按 {@link BackpressureStrategy} 处理，
 *       既不会因无界队列导致 OOM，也不会静默丢弃任务；</li>
 *   <li><b>本地 WAL 兜底</b>：默认 SPILL 策略把溢出记录落本地，并在存储恢复后重放；</li>
 *   <li><b>重试 + 死信</b>：指数退避重试，耗尽后进死信并计入指标 + 触发失败告警；</li>
 *   <li><b>链顺序确定性</b>：{@code seq} 在入队时（请求线程内）分配，
 *       由单消费者线程串行链接与投递；消费者 &gt; 1 时自动关闭链以保持正确性；</li>
 *   <li><b>停机排空</b>：{@link #stop()} 排空队列 + flush WAL，避免关机丢数据。</li>
 * </ul>
 *
 * <p>线程模型：{@link #submit} 由业务请求线程调用，{@link #start()} 启动的消费者线程
 * 负责投递；{@link #stop()} 停止消费并排空队列，不关闭 {@link AuditStore} 与
 * {@link AuditMetrics}（其生命周期由装配方持有）。
 */
public class AuditPipeline {

	private static final Logger log = LoggerFactory.getLogger(AuditPipeline.class);

	/**
	 * 流水线配置。
	 *
	 * <p>所有字段在紧凑构造器中归一化：非正数取默认值，因此构造器不会拒绝非法入参。
	 * 各字段的取值边界见紧凑构造器的参数说明。
	 */
	public record Config(
		int queueCapacity,
		int batchSize,
		long batchTimeoutMillis,
		int consumerThreads,
		BackpressureStrategy strategy,
		long blockTimeoutMillis,
		int maxRetries,
		long retryBackoffMillis,
		boolean chainEnabled,
		int recentCacheSize) {

		/**
		 * 归一化各字段：非正数替换为默认值，并落实链与消费者数量的约束。
		 *
		 * <p>入参不会被拒绝，非法值一律静默归一，因此 {@code Config} 的实例字段始终可用。
		 *
		 * @param queueCapacity      有界队列容量；非正数取 8192，实际容量会向上取整为 2 的幂
		 * @param batchSize          单批投递的记录数上限；非正数取 100
		 * @param batchTimeoutMillis 消费者无数据时的等待毫秒数；非正数取 200
		 * @param consumerThreads    消费线程数，至少为 1；{@code chainEnabled} 为 {@code true}
		 *                           时被强制为 1，因为哈希链是有状态串行结构，多消费者并发链接
		 *                           会使 {@code prevHash} 分叉，产生校验必然失败的链
		 * @param strategy           队列满时的背压策略；{@code null} 取 {@link BackpressureStrategy#SPILL}
		 * @param blockTimeoutMillis {@code BLOCK} 策略的最长等待毫秒数，超时后降级写 WAL；非正数取 2000
		 * @param maxRetries         存储投递失败后的重试次数；负数取 3，{@code 0} 表示不重试
		 * @param retryBackoffMillis 首次重试前的退避毫秒数，之后每次翻倍且不超过 30s；非正数取 200
		 * @param chainEnabled       是否启用哈希链；启用后投递串行化，吞吐下降
		 * @param recentCacheSize    {@link AuditPipeline#recentRecords()} 保留的最近记录条数；非正数取 10000
		 */
		public Config {
			queueCapacity = queueCapacity <= 0 ? 8192 : queueCapacity;
			batchSize = batchSize <= 0 ? 100 : batchSize;
			batchTimeoutMillis = batchTimeoutMillis <= 0 ? 200L : batchTimeoutMillis;
			consumerThreads = Math.max(1, consumerThreads);
			strategy = strategy == null ? BackpressureStrategy.SPILL : strategy;
			blockTimeoutMillis = blockTimeoutMillis <= 0 ? 2000L : blockTimeoutMillis;
			maxRetries = maxRetries < 0 ? 3 : maxRetries;
			retryBackoffMillis = retryBackoffMillis <= 0 ? 200L : retryBackoffMillis;
			recentCacheSize = recentCacheSize <= 0 ? 10_000 : recentCacheSize;
			// 哈希链是有状态串行结构：多消费者并发链接会使 prevHash 分叉，
			// 产生一条校验必然失败的链。在此强制为单消费者，使该约束在所有装配路径上一致生效。
			if (chainEnabled && consumerThreads > 1) {
				consumerThreads = 1;
			}
		}

		/**
		 * 默认配置。
		 *
		 * @return 容量 8192、单消费者、SPILL 背压、关闭哈希链的配置
		 */
		public static Config defaults() {
			return new Config(8192, 100, 200L, 1, BackpressureStrategy.SPILL, 2000L, 3, 200L, false, 10_000);
		}
	}

	private final Config config;
	private final BoundedRingQueue<AuditRecord> queue;
	private final AuditStore store;
	private final AuditChain chain;
	private final AuditMetrics metrics;
	private final AuditFailureHandler failureHandler;
	private final AuditJournal journal;
	private final FileAuditJournal fileJournal;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicLong sequence = new AtomicLong(0L);
	private final AtomicLong droppedRecords = new AtomicLong(0L);
	private final List<Thread> consumers = new CopyOnWriteArrayList<>();
	private final Deque<AuditRecord> recentRecords = new ArrayDeque<>();

	private volatile long lastDeliveredSeq = 0L;

	/**
	 * 链状态持久化；为 {@code null} 时不做持久化，链在重启后无法衔接。
	 *
	 * <p>仅在 {@link #setChainStore} 或构造器注入后使用，{@link #start()} 之后修改不会生效。
	 */
	private volatile io.github.pkyit.auditlog.core.api.AuditChainStore chainStore;

	/**
	 * 创建流水线（不持久化链状态）。
	 *
	 * <p>等价于传入 {@code chainStore} 为 {@code null} 的构造器，链状态不会跨重启保留。
	 *
	 * @param config         流水线配置；{@code null} 取 {@link Config#defaults()}
	 * @param store          审计存储，{@link #submit} 不写入该存储，落库由消费线程完成
	 * @param chain          哈希链实现；{@code null} 取 {@code Sha256AuditChain.noop()}（不链接）
	 * @param metrics        指标实现；{@code null} 取 {@code AuditMetrics.noop()}（不上报）
	 * @param failureHandler 失败处理器；{@code null} 取 {@code AuditFailureHandler.logging()}
	 * @param journal        本地兜底日志；{@code null} 时 SPILL 与死信无落点，溢出记录被丢弃
	 */
	public AuditPipeline(Config config,
			AuditStore store,
			AuditChain chain,
			AuditMetrics metrics,
			AuditFailureHandler failureHandler,
			AuditJournal journal) {
		this(config, store, chain, metrics, failureHandler, journal, null);
	}

	/**
	 * 创建带链状态持久化的流水线。
	 *
	 * <p>{@code chainStore} 使哈希链跨重启衔接：{@link #start()} 时从该存储恢复链头与
	 * {@code seq} 基准，每批投递成功后回写。不提供时重启后链头从 GENESIS 重新开始，
	 * 跨重启的链校验会判定为被篡改。
	 *
	 * @param config         流水线配置；{@code null} 取 {@link Config#defaults()}
	 * @param store          审计存储；链状态不可用时会尝试从其回读链尾
	 * @param chain          哈希链实现；{@code null} 取 noop 实现（不链接）
	 * @param metrics        指标实现；{@code null} 取 noop 实现
	 * @param failureHandler 失败处理器；{@code null} 取日志实现
	 * @param journal        本地兜底日志；{@code null} 时溢出记录无落点
	 * @param chainStore     链状态存储；{@code null} 表示不持久化链状态
	 */
	public AuditPipeline(Config config,
			AuditStore store,
			AuditChain chain,
			AuditMetrics metrics,
			AuditFailureHandler failureHandler,
			AuditJournal journal,
			io.github.pkyit.auditlog.core.api.AuditChainStore chainStore) {
		this.config = config == null ? Config.defaults() : config;
		this.queue = new BoundedRingQueue<>(this.config.queueCapacity());
		this.store = store;
		this.chain = chain == null ? io.github.pkyit.auditlog.core.chain.Sha256AuditChain.noop() : chain;
		this.metrics = metrics == null ? AuditMetrics.noop() : metrics;
		this.failureHandler = failureHandler == null ? AuditFailureHandler.logging() : failureHandler;
		this.journal = journal;
		this.fileJournal = journal instanceof FileAuditJournal file ? file : null;
		this.chainStore = chainStore;
		if (this.chain.isEnabled() && this.config.consumerThreads() > 1) {
			log.warn("[audit-log] 哈希链与多消费者不兼容，消费者线程数已强制为 1 以保证链完整");
		}
	}

	/**
	 * 设置链状态持久化。
	 *
	 * <p>必须在 {@link #start()} 之前调用：{@link #start()} 只在启动时恢复一次链状态，
	 * 启动后再设置不会触发恢复，链头仍是启动时的值。
	 *
	 * @param chainStore 链状态存储；{@code null} 表示不做持久化，链跨重启会断
	 */
	public void setChainStore(io.github.pkyit.auditlog.core.api.AuditChainStore chainStore) {
		this.chainStore = chainStore;
	}

	// ---- 生命周期 ----

	/**
	 * 启动流水线：打开 WAL、重放历史、启动消费者。
	 *
	 * <p>幂等：已在运行时直接返回，不会重复启动消费者或重复重放。
	 *
	 * <p>启动顺序不可调整：必须先恢复链状态再重放，否则重放会以 GENESIS 重新链接，
	 * 把链头推进到错误的哈希上；必须先重放历史再消费实时数据，否则 {@code seq}
	 * 不再单调，链会出现空洞。
	 *
	 * <p>WAL 打开失败不阻断启动，仅通过 {@code failureHandler} 上报 —— 此时 SPILL 策略
	 * 不可用，队列溢出将退化为丢弃。
	 */
	public void start() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		if (fileJournal != null) {
			try {
				fileJournal.open();
			}
			catch (Exception error) {
				failureHandler.onFailure("journal", "本地兜底日志启动失败，SPILL 策略将不可用", List.of(), error);
			}
		}
		// 必须先恢复链状态再重放：顺序颠倒会使重放以 GENESIS 重新链接，
		// 把链头推进到错误的哈希上
		restoreChainState();
		// 先重放历史再消费实时数据，保证 seq 顺序单调，避免链出现空洞
		replay();
		for (int index = 0; index < config.consumerThreads(); index++) {
			Thread thread = new Thread(this::consumeLoop, "audit-log-consumer-" + index);
			thread.setDaemon(true);
			consumers.add(thread);
			thread.start();
		}
		log.info("[audit-log] 审计流水线已启动 store={} queueCapacity={} batchSize={} consumers={} strategy={} chain={}",
			store.getName(), config.queueCapacity(), config.batchSize(), config.consumerThreads(),
			config.strategy(), chain.isEnabled());
	}

	/**
	 * 停机：停止消费、排空队列与 WAL。
	 *
	 * <p>幂等：未运行时直接返回。排空会尽力交付队列中剩余记录，但本方法不关闭
	 * {@link AuditStore}，其生命周期由装配方持有。
	 *
	 * <p>消费者线程若在 10s 内未退出（例如仍处于重试退避中），排空将与其并发执行，
	 * 此时链序可能出现交错，因此只记录告警而不强制终止线程 —— 中断退避会让正在重试的
	 * 整批记录丢失。
	 */
	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		for (Thread consumer : consumers) {
			consumer.interrupt();
		}
		for (Thread consumer : consumers) {
			// 等消费者真正退出：parkNanos 退避不响应中断，被中断时会先把整批落死信再退出，
			// 不会静默丢数据，因此单次 join 可能超时返回。若此时就去 drainAll 并 link，
			// 会与仍在运行的消费者并发链接，导致 seq/链序交错。
			// 因此循环等待，超时则告警暴露风险，而不是掩盖它。
			try {
				consumer.join(10_000L);
			}
			catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				break;
			}
			if (consumer.isAlive()) {
				log.warn("[audit-log] 消费者线程 {} 在 10s 内未退出（可能仍处于重试退避中）——"
					+ "停机排空将与其并发执行，链序可能出现交错。请检查存储是否长时间不可用。",
					consumer.getName());
			}
		}
		// 排空过程中队列可能仍在接收（例如容器仍在处理请求），因此多轮排空
		for (int round = 0; round < 5; round++) {
			List<AuditRecord> remaining = queue.drainAll();
			if (remaining.isEmpty()) {
				break;
			}
			deliver(remaining);
		}
		List<AuditRecord> last = queue.drainAll();
		if (!last.isEmpty()) {
			deliver(last);
		}
		if (fileJournal != null) {
			fileJournal.close();
		}
		log.info("[audit-log] 审计流水线已停止 deliveredSeq={} queueDepth={} journaled={} dropped={}",
			lastDeliveredSeq, queue.size(), fileJournal == null ? 0L : fileJournal.journaledCount(), droppedCount());
	}

	// ---- 提交 ----

	/**
	 * 提交一条审计记录（由业务请求线程调用）。
	 *
	 * <p>只做三件轻量的事：分配 {@code seq}、按策略入队、更新指标，不执行任何 I/O。
	 * 除策略显式配置为 {@link BackpressureStrategy#FAIL_FAST}（有意的强一致选择）外，
	 * 不会因审计组件自身的问题抛异常打断业务。
	 *
	 * @param record 待提交记录；{@code null} 直接返回 {@code false}
	 * @return {@code true} 已接受（入队或落 WAL）；{@code false} 被丢弃
	 */
	public boolean submit(AuditRecord record) {
		return submit(record, false);
	}

	/**
	 * 提交一条审计记录。
	 *
	 * <p>{@code noQueue} 为 {@code true} 时跳过队列直接投递，由当前线程承担存储 I/O
	 * 及其全部重试与死信处理，且不再经过背压策略 —— 仅用于停机阶段的最终刷新。
	 *
	 * @param record  待提交记录；{@code null} 直接返回 {@code false}
	 * @param noQueue 为 {@code true} 时跳过队列直接投递
	 * @return {@code true} 已接受（入队、落 WAL 或直接投递完成）；
	 *         {@code false} 记录为 {@code null}，或被背压策略丢弃
	 */
	public boolean submit(AuditRecord record, boolean noQueue) {
		if (record == null) {
			return false;
		}
		long seq = record.getSeq() > 0 ? record.getSeq() : sequence.incrementAndGet();
		AuditRecord toStore = record.getSeq() == seq ? record : record.toBuilder().seq(seq).build();

		if (noQueue) {
			deliver(List.of(toStore));
			return true;
		}
		if (!running.get()) {
			// 未启动或已停机：不能静默丢弃，先尝试 WAL，再计数
			return spill(toStore, "pipeline-not-running");
		}
		return switch (config.strategy()) {
			case BLOCK -> offerBlocking(toStore);
			case DROP_OLDEST -> offerDropOldest(toStore);
			case DROP_NEWEST -> offerDropNewest(toStore);
			case FAIL_FAST -> offerFailFast(toStore);
			case SPILL -> offerSpill(toStore);
		};
	}

	private boolean offerBlocking(AuditRecord record) {
		long deadline = System.nanoTime() + config.blockTimeoutMillis() * 1_000_000L;
		boolean blocked = false;
		while (System.nanoTime() < deadline) {
			if (queue.offer(record)) {
				metrics.increment(AuditMetricNames.ENQUEUED);
				updateDepthGauge();
				return true;
			}
			// 仅当队列确实满、入队失败时才计一次背压事件：若在方法入口无条件递增，
			// BACKPRESSURE 记的会是「每个请求的尝试次数」，该指标将失去告警语义
			if (!blocked) {
				blocked = true;
				metrics.increment(AuditMetricNames.BACKPRESSURE);
			}
			java.util.concurrent.locks.LockSupport.parkNanos(200_000L);
		}
		// 阻塞超时后仍不丢数据：降级落 WAL
		log.warn("[audit-log] BLOCK 策略等待超时，降级写入本地兜底日志 id={}", record.getId());
		return spill(record, "block-timeout");
	}

	private boolean offerDropOldest(AuditRecord record) {
		BoundedRingQueue.DropOutcome<AuditRecord> outcome = queue.dropOldestAndOffer(record);
		if (!outcome.accepted()) {
			// 新记录未能入队：计入丢弃，不记为入队成功
			metrics.increment(AuditMetricNames.BACKPRESSURE);
			metrics.increment(AuditMetricNames.DROPPED);
			droppedRecords.incrementAndGet();
			failureHandler.onFailure("enqueue",
				"队列已满且无法完成替换，按 DROP_OLDEST 丢弃新记录 seq=" + record.getSeq(),
				List.of(record), null);
			return false;
		}
		if (outcome.replacedOldest()) {
			// 仅当确实「丢弃最旧以腾位」时，才各计一次背压事件与丢弃
			metrics.increment(AuditMetricNames.BACKPRESSURE);
			metrics.increment(AuditMetricNames.DROPPED);
			droppedRecords.incrementAndGet();
			failureHandler.onFailure("enqueue",
				"队列已满，按 DROP_OLDEST 策略丢弃最旧记录 seq=" + outcome.discarded().getSeq(),
				List.of(outcome.discarded()), null);
		}
		metrics.increment(AuditMetricNames.ENQUEUED);
		updateDepthGauge();
		return true;
	}

	private boolean offerDropNewest(AuditRecord record) {
		if (queue.offer(record)) {
			metrics.increment(AuditMetricNames.ENQUEUED);
			updateDepthGauge();
			return true;
		}
		metrics.increment(AuditMetricNames.BACKPRESSURE);
		metrics.increment(AuditMetricNames.DROPPED);
		droppedRecords.incrementAndGet();
		failureHandler.onFailure("enqueue",
			"队列已满，按 DROP_NEWEST 策略丢弃记录 seq=" + record.getSeq(), List.of(record), null);
		return false;
	}

	private boolean offerFailFast(AuditRecord record) {
		if (queue.offer(record)) {
			metrics.increment(AuditMetricNames.ENQUEUED);
			updateDepthGauge();
			return true;
		}
		metrics.increment(AuditMetricNames.DROPPED);
		droppedRecords.incrementAndGet();
		failureHandler.onFailure("enqueue", "队列已满且策略为 FAIL_FAST，中断业务请求", List.of(record), null);
		// 这是使用者显式选择的语义：审计不成功，业务不继续
		throw new AuditUnavailableException("审计队列已满，按 FAIL_FAST 策略拒绝该操作");
	}

	private boolean offerSpill(AuditRecord record) {
		if (queue.offer(record)) {
			metrics.increment(AuditMetricNames.ENQUEUED);
			updateDepthGauge();
			return true;
		}
		metrics.increment(AuditMetricNames.BACKPRESSURE);
		return spill(record, "queue-full");
	}

	/**
	 * 写入本地兜底日志。
	 *
	 * <p>WAL 不可用或拒绝写入时计数并通过 {@code failureHandler} 告警，不会静默丢弃。
	 *
	 * @param record 待兜底写入的记录
	 * @param reason 触发兜底的原因，写入失败上下文
	 * @return {@code true} 已写入 WAL；{@code false} 未配置 WAL 或写入被拒，记录已丢弃
	 */
	private boolean spill(AuditRecord record, String reason) {
		if (journal == null) {
			metrics.increment(AuditMetricNames.DROPPED);
			droppedRecords.incrementAndGet();
			failureHandler.onFailure("journal", "队列已满且未配置本地兜底日志，记录被丢弃 reason=" + reason,
				List.of(record), null);
			return false;
		}
		boolean accepted = journal.append(record);
		if (!accepted) {
			metrics.increment(AuditMetricNames.DROPPED);
			droppedRecords.incrementAndGet();
			failureHandler.onFailure("journal", "本地兜底日志拒绝写入，记录被丢弃 reason=" + reason,
				List.of(record), null);
		}
		return accepted;
	}

	// ---- 消费 ----

	private void consumeLoop() {
		// 队列的 poll 使用 parkNanos 而非可中断阻塞，因此循环不依赖 InterruptedException 退出：
		// 停机由 running 标志与 interrupt 共同触发（interrupt 使 parkNanos 立即返回，
		// 循环随后重新检查 running）
		while (running.get()) {
			try {
				List<AuditRecord> batch = queue.pollBatch(config.batchSize());
				if (batch.isEmpty()) {
					AuditRecord single = queue.poll(config.batchTimeoutMillis() * 1_000_000L);
					if (single == null) {
						continue;
					}
					batch = new ArrayList<>(1);
					batch.add(single);
					// 尽量顺带把已有积压一起组批，提高吞吐
					batch.addAll(queue.pollBatch(config.batchSize() - 1));
				}
				updateDepthGauge();
				deliver(batch);
			}
			catch (Throwable error) {
				if (error instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
					Thread.currentThread().interrupt();
					break;
				}
				// 消费线程不能因单批异常而退出，否则审计会从此静默停止
				failureHandler.onFailure("consume", "消费循环异常，已继续", List.of(), error);
			}
		}
	}

	/**
	 * 链接（可选）并投递一批记录。
	 *
	 * <p>本方法不负责失败重投：批次投递失败时由 {@link #sendWithRetry} 写入死信并返回
	 * {@code false}，此时链状态与 checkpoint 均不推进。
	 */
	private void deliver(List<AuditRecord> batch) {
		if (batch == null || batch.isEmpty()) {
			return;
		}
		List<AuditRecord> sorted = batch.size() > 1 ? new ArrayList<>(batch) : batch;
		if (batch.size() > 1) {
			// 重放与实时数据混合时必须按 seq 排序，否则链会错位
			sorted.sort(Comparator.comparingLong(AuditRecord::getSeq));
		}
		List<AuditRecord> prepared = new ArrayList<>(sorted.size());
		for (AuditRecord record : sorted) {
			prepared.add(chain.isEnabled() ? linkForDelivery(record) : record);
		}
		boolean delivered = sendWithRetry(prepared);
		if (delivered) {
			long maxSeq = prepared.stream().mapToLong(AuditRecord::getSeq).max().orElse(lastDeliveredSeq);
			lastDeliveredSeq = Math.max(lastDeliveredSeq, maxSeq);
			remember(prepared);
			// 顺序要求：先持久化链状态，再推进日志 checkpoint。
			// 两者间的窗口越小，崩溃后重放「已投递记录」的概率越低。
			persistChainState();
			if (journal != null) {
				journal.checkpoint(lastDeliveredSeq);
			}
		}
	}

	/**
	 * 为一次投递准备记录：已带链信息的不重复链接，未链的按当前链头链接。
	 *
	 * <p>重放路径的记录可能已经带链（例如 WAL 中的记录在崩溃前已链接过）。
	 * 对它们再次 {@code link} 会使链头前进到「基于错误前驱算出的哈希」，
	 * 而存储中的该记录不会被覆盖（主键幂等），于是内存链头与存储内容永久不一致，
	 * 此后所有实时记录的 {@code prevHash} 都指向存储中不存在的值。
	 *
	 * <p>因此已有 {@code recordHash} 的记录原样透传（它已自洽），同时把链头对齐到它，
	 * 保证后续记录能正确衔接。
	 *
	 * @param record 待投递记录
	 * @return 可直接投递的记录：已带 {@code recordHash} 的原样返回，否则返回链接后的记录
	 */
	private AuditRecord linkForDelivery(AuditRecord record) {
		if (record.getRecordHash() != null) {
			// 已链接过：不重算，但要把链头对齐，避免后续记录接在错误的前驱上
			io.github.pkyit.auditlog.core.chain.Hasher.restoreIfSupported(chain, record.getRecordHash());
			return record;
		}
		return chain.link(record);
	}

	/**
	 * 从持久化状态恢复链头与 {@code seq} 基准。
	 *
	 * <p>恢复失败通过 {@code failureHandler} 上报，不静默：链头未恢复意味着重启后
	 * 链无法衔接，跨重启校验会报被篡改。链实现不支持恢复时同样上报并返回，
	 * 此时后续记录仍会正常投递，但链的连续性已丢失。
	 */
	private void restoreChainState() {
		if (!chain.isEnabled()) {
			return;
		}
		io.github.pkyit.auditlog.core.api.ChainState state = null;
		if (chainStore != null) {
			try {
				state = chainStore.load();
			}
			catch (RuntimeException error) {
				failureHandler.onFailure("chain-restore", "链状态读取失败", List.of(), error);
			}
		}
		// 兜底：状态文件缺失/损坏时，直接从存储回读链尾
		if (state == null || state.head() == null) {
			io.github.pkyit.auditlog.core.api.ChainState recovered = null;
			if (store != null) {
				try {
					recovered = store.recoverChainState();
				}
				catch (RuntimeException error) {
					failureHandler.onFailure("chain-restore", "从存储回读链尾失败", List.of(), error);
				}
			}
			if (recovered != null && recovered.head() != null) {
				state = recovered;
				log.info("[audit-log] 链状态文件不可用，已从存储回读链尾恢复：seq={}",
					recovered.lastDeliveredSeq());
			}
		}
		if (state == null || state.head() == null) {
			// 冷启动：无历史链状态属正常情况
			log.info("[audit-log] 未发现历史链状态，哈希链从 GENESIS 开始");
			return;
		}
		boolean restored = io.github.pkyit.auditlog.core.chain.Hasher.restoreIfSupported(chain, state.head());
		if (!restored) {
			failureHandler.onFailure("chain-restore",
				"链状态恢复失败：当前链实现不支持从持久化状态恢复链头，"
					+ "重启后哈希链将无法衔接（跨重启校验会报被篡改）", List.of(), null);
			return;
		}
		// 用持久化的 seq 基准初始化序列号，避免重启后 seq 回退
		if (state.lastDeliveredSeq() > 0L) {
			sequence.accumulateAndGet(state.lastDeliveredSeq(), Math::max);
			lastDeliveredSeq = state.lastDeliveredSeq();
		}
		// 日志 checkpoint 取较大值：避免重复投递已落库的记录
		if (state.lastDeliveredSeq() > 0L && journal != null) {
			try {
				journal.checkpoint(state.lastDeliveredSeq());
			}
			catch (RuntimeException error) {
				failureHandler.onFailure("chain-restore", "推进日志 checkpoint 失败", List.of(), error);
			}
		}
		log.info("[audit-log] 已恢复链状态：head={} lastDeliveredSeq={}",
			abbreviate(state.head()), state.lastDeliveredSeq());
	}

	/**
	 * 持久化当前链状态（每批成功投递后调用）。
	 *
	 * <p>未配置 {@code chainStore} 或链未启用时不执行任何操作。写入失败通过
	 * {@code failureHandler} 上报：状态丢失意味着重启后链会断。
	 */
	private void persistChainState() {
		if (chainStore == null || !chain.isEnabled()) {
			return;
		}
		try {
			chainStore.save(new io.github.pkyit.auditlog.core.api.ChainState(
				chain.currentHead(), lastDeliveredSeq));
		}
		catch (RuntimeException error) {
			// 保存失败必须告警：状态丢失会导致重启后链无法衔接
			failureHandler.onFailure("chain-state",
				"链状态持久化失败：重启后哈希链可能无法衔接", List.of(), error);
		}
	}

	private static String abbreviate(String value) {
		if (value == null) {
			return "null";
		}
		return value.length() <= 16 ? value : value.substring(0, 16) + "...";
	}

	private boolean sendWithRetry(List<AuditRecord> batch) {
		long backoff = config.retryBackoffMillis();
		for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
			long start = System.nanoTime();
			try {
				store.save(batch);
				metrics.recordDuration(AuditMetricNames.STORE_LATENCY,
					(System.nanoTime() - start) / 1_000_000L);
				metrics.increment(AuditMetricNames.DELIVERED, batch.size());
				return true;
			}
			catch (Throwable error) {
				metrics.increment(AuditMetricNames.STORE_FAILED);
				boolean lastAttempt = attempt >= config.maxRetries();
				boolean interrupted = false;
				if (!lastAttempt) {
					metrics.increment(AuditMetricNames.STORE_RETRIED);
					failureHandler.onFailure("store",
						"投递失败，第 " + (attempt + 1) + " 次重试，" + batch.size() + " 条记录", batch, error);
					interrupted = sleepForBackoff(backoff);
					backoff = Math.min(backoff * 2, 30_000L);
				}
				// 重试耗尽或退避等待被中断（例如应用正在停机）时，
				// 都必须把本批记录写入死信；直接返回等于在停机瞬间丢掉一批审计数据
				if (lastAttempt || interrupted) {
					String reason = lastAttempt
						? "store-retry-exhausted:" + error.getClass().getSimpleName()
						: "store-retry-interrupted:" + error.getClass().getSimpleName();
					failureHandler.onFailure("store",
						"投递失败（" + reason + "），" + batch.size() + " 条记录进入死信", batch, error);
					deadLetter(batch, reason);
					return false;
				}
			}
		}
		return false;
	}

	/**
	 * 退避等待（不可被中断放弃）。
	 *
	 * <p>使用 {@link java.util.concurrent.locks.LockSupport#parkNanos(long)} 而非
	 * {@code Thread.sleep}：后者被中断时抛异常，调用方若据此放弃剩余重试，
	 * 停机瞬间正在重试的整批审计记录就会丢失。停机安全性由调用方的 {@link #stop()}
	 * 负责（它 join 消费者并最终排空队列），因此这里把重试走完、让无法投递的记录
	 * 落进死信，优于提前退出。
	 *
	 * @param millis 退避毫秒数；非正数时不等待
	 * @return 固定返回 {@code false}，表示未因中断而放弃重试
	 */
	private boolean sleepForBackoff(long millis) {
		if (millis <= 0L) {
			return false;
		}
		// parkNanos 不抛 InterruptedException；中断状态由 stop() 与循环条件 running 处理
		java.util.concurrent.locks.LockSupport.parkNanos(millis * 1_000_000L);
		return false;
	}

	/**
	 * 将一批记录写入死信并计数。
	 *
	 * <p>这是记录被丢弃前的最后一道防线：未配置 {@code journal} 时仅计数，记录内容不再落盘。
	 *
	 * @param batch  写入死信的记录
	 * @param reason 死信原因，随记录一起落盘
	 */
	private void deadLetter(List<AuditRecord> batch, String reason) {
		for (AuditRecord record : batch) {
			metrics.increment(AuditMetricNames.DEADLETTER);
			if (journal != null) {
				journal.appendDeadLetter(record, reason);
			}
		}
	}

	/**
	 * 重放本地兜底日志中尚未投递的记录。
	 *
	 * <p>同步执行，由调用线程完成投递及其重试；{@link #start()} 在启动消费者之前调用，
	 * 使重放记录的 {@code seq} 先于实时数据，链不会出现空洞。流水线未运行时立即返回 0。
	 *
	 * @return 实际重放的记录条数；未配置 {@code journal} 时返回 0
	 */
	public int replay() {
		if (journal == null) {
			return 0;
		}
		int total = 0;
		while (running.get()) {
			List<AuditRecord> pending = journal.readPending(config.batchSize());
			if (pending.isEmpty()) {
				break;
			}
			deliver(pending);
			total += pending.size();
			metrics.increment(AuditMetricNames.REPLAYED, pending.size());
			if (pending.size() < config.batchSize()) {
				break;
			}
		}
		if (total > 0) {
			log.info("[audit-log] 已从本地兜底日志重放 {} 条记录", total);
		}
		return total;
	}

	private void remember(List<AuditRecord> batch) {
		synchronized (recentRecords) {
			for (AuditRecord record : batch) {
				recentRecords.addLast(record);
			}
			while (recentRecords.size() > config.recentCacheSize()) {
				recentRecords.pollFirst();
			}
		}
	}

	private void updateDepthGauge() {
		metrics.gauge(AuditMetricNames.QUEUE_DEPTH, queue.size());
		if (journal != null) {
			metrics.gauge(AuditMetricNames.JOURNAL_PENDING, journal.pendingCount());
		}
	}

	// ---- 查询（供健康检查 / 验收断言） ----

	/**
	 * 最近投递的记录快照。
	 *
	 * <p>用于链校验演示与诊断，仅包含内存缓存中的记录，条数上限由
	 * {@code Config.recentCacheSize} 决定。
	 *
	 * @return 不可变快照，按投递顺序排列；调用方修改它不影响流水线状态
	 */
	public List<AuditRecord> recentRecords() {
		synchronized (recentRecords) {
			return List.copyOf(recentRecords);
		}
	}

	/**
	 * 校验已投递记录的哈希链。
	 *
	 * <p>校验范围限于 {@link #recentRecords()}，即内存缓存内的记录；链未启用时
	 * 返回的结果由链实现决定。
	 *
	 * @return 链校验结果
	 */
	public AuditChain.ChainVerification verifyChain() {
		return chain.verify(recentRecords());
	}

	/**
	 * 当前队列积压深度。
	 *
	 * @return 近似值，仅供健康检查使用
	 */
	public int queueDepth() {
		return queue.size();
	}

	/**
	 * 最近一次成功投递记录的 {@code seq}。
	 *
	 * @return 单调不减；尚无成功投递时为 0，恢复链状态后会跳到持久化值
	 */
	public long lastDeliveredSeq() {
		return lastDeliveredSeq;
	}

	/**
	 * 因背压策略而被真正丢弃的记录数。
	 *
	 * @return 累计值，只增不减；非 0 表示审计数据存在缺口
	 */
	public long droppedCount() {
		return droppedRecords.get();
	}

	/**
	 * 流水线配置。
	 *
	 * @return 实际生效的配置，字段已按 {@link Config} 的归一化规则处理
	 */
	public Config config() {
		return config;
	}

	/**
	 * 审计存储。
	 *
	 * @return 构造时注入的存储实例
	 */
	public AuditStore store() {
		return store;
	}

	/**
	 * 哈希链实现。
	 *
	 * @return 构造时注入的链实例；未注入时为 noop 实现
	 */
	public AuditChain chain() {
		return chain;
	}

	/**
	 * 本地兜底日志。
	 *
	 * @return 构造时注入的日志实例；未配置时为 {@code null}，此时溢出记录无落点
	 */
	public AuditJournal journal() {
		return journal;
	}

	/**
	 * 审计不可用（{@link BackpressureStrategy#FAIL_FAST} 策略专用）。
	 *
	 * <p>由 {@link #submit} 在 {@code FAIL_FAST} 策略下、队列满时抛出，用于中断业务请求。
	 * 抛出前记录已计入 {@code DROPPED} 并上报 {@code failureHandler}，调用方无需重复上报。
	 */
	public static class AuditUnavailableException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		/**
		 * 创建异常。
		 *
		 * @param message 异常描述
		 */
		public AuditUnavailableException(String message) {
			super(message);
		}
	}
}
