package io.github.pkyit.auditlog.core.pipeline;

import io.github.pkyit.auditlog.core.model.AuditRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 有界环形队列（多生产者 / 多消费者，无锁）。
 *
 * <p>本实现面向审计流水线的两项要求，这两项在 {@code ArrayBlockingQueue} 上无法同时满足：
 * 入队结果必须区分「入队成功」与「队列已满、需要走背压分支」，且 {@code DROP_OLDEST}
 * 策略要求能安全覆盖最旧槽位。并发控制采用「数组槽位 + 序号戳」，使锁竞争不会成为高并发下的新瓶颈。
 *
 * <p>槽位协议：每个槽位保存 {@code sequence}，生产者用 CAS 将其从 {@code -1} 置为自己的序号，
 * 消费者用 CAS 将其从序号复位为 {@code -1}。这与 LMAX Disruptor 的单槽位序号方案等价，
 * 但为变长元素保存的是对象引用而非预分配事件。
 *
 * @param <E> 元素类型
 */
public final class BoundedRingQueue<E> {

	private static final int IDLE = -1;

	private final Object[] slots;
	private final long[] sequences;
	private final int capacity;
	private final int mask;

	private final AtomicLong produced = new AtomicLong();
	private final AtomicLong consumed = new AtomicLong();

	/**
	 * 创建队列。
	 *
	 * @param requestedCapacity 期望容量；实际容量向上取整为不小于 2 的 2 的幂，
	 *                          由 {@link #capacity()} 返回
	 */
	public BoundedRingQueue(int requestedCapacity) {
		int normalized = 1;
		while (normalized < Math.max(2, requestedCapacity)) {
			normalized <<= 1;
		}
		this.capacity = normalized;
		this.mask = normalized - 1;
		this.slots = new Object[normalized];
		this.sequences = new long[normalized];
		java.util.Arrays.fill(this.sequences, IDLE);
	}

	/**
	 * 实际容量。
	 *
	 * @return 不小于 2 的 2 的幂，可能大于构造时请求的容量
	 */
	public int capacity() {
		return capacity;
	}

	/**
	 * 当前积压元素个数。
	 *
	 * @return 近似值，并发读写时可能瞬时偏离真实值，仅用于指标上报
	 */
	public int size() {
		long size = produced.get() - consumed.get();
		return (int) Math.max(0L, Math.min(size, capacity));
	}

	/**
	 * 队列是否为空。
	 *
	 * @return 当前无积压元素时为 {@code true}；并发场景下该结果仅代表调用瞬间
	 */
	public boolean isEmpty() {
		return size() == 0;
	}

	/**
	 * 尝试入队，不阻塞。
	 *
	 * @param element 待入队元素
	 * @return {@code true} 入队成功；{@code false} 表示队列已满（调用方执行背压策略）
	 * @throws NullPointerException {@code element} 为 {@code null}
	 */
	public boolean offer(E element) {
		if (element == null) {
			throw new NullPointerException("element must not be null");
		}
		while (true) {
			long current = produced.get();
			if (current - consumed.get() >= capacity) {
				return false;
			}
			if (!produced.compareAndSet(current, current + 1)) {
				continue;
			}
			int index = (int) (current & mask);
			while (sequences[index] != IDLE) {
				// 该槽位尚未被消费者释放（仅可能在极端抢占下短暂出现）
				Thread.onSpinWait();
			}
			slots[index] = element;
			sequences[index] = current;
			return true;
		}
	}

	/**
	 * 覆盖最旧元素（{@link BackpressureStrategy#DROP_OLDEST} 使用）。
	 *
	 * <p>整个过程必须是循环重试而非「先 {@code poll()} 再 {@code offer()}」两步：
	 * 两步之间不具原子性，并发生产者竞争下 {@code offer} 可能失败，此时新元素被静默丢弃，
	 * 而调用方只看到「丢弃了最旧元素」并照常计一次 ENQUEUED ——
	 * 数据已丢失却被记为入队成功，直接破坏「指标可用于证明不丢数据」这一前提。
	 *
	 * <p>因此本方法保证：只要发生过丢弃，就继续尝试放入新元素；仅当队列确实为空
	 * （{@code poll} 返回 {@code null}）时才回退到普通 {@link #offer}。
	 * 极端竞争下仍无法入队时返回失败，不静默丢弃新元素。
	 *
	 * <p>返回值同时给出「是否入队成功」与「是否发生了替换」，调用方无需再读队列状态做二次判断
	 * （那会引入竞态）。
	 *
	 * @param element 待入队元素
	 * @return 本次入队结果及被丢弃的最旧元素；队列已满且替换失败时 {@code accepted} 为 {@code false}
	 * @throws NullPointerException {@code element} 为 {@code null}
	 */
	@SuppressWarnings("unchecked")
	public DropOutcome<E> dropOldestAndOffer(E element) {
		if (element == null) {
			throw new NullPointerException("element must not be null");
		}
		// 至多重试有限次：每次重试都伴随一次成功 poll，因此不会活锁
		for (int attempt = 0; attempt < 64; attempt++) {
			E discarded = (E) poll();
			if (discarded == null) {
				// 队列已空：无需丢弃，直接尝试入队
				return offer(element) ? DropOutcome.ofAccepted() : DropOutcome.ofRejected();
			}
			if (offer(element)) {
				return DropOutcome.ofReplaced(discarded);
			}
			// offer 失败说明槽位被其他生产者抢占：继续丢弃下一个最旧元素再试，
			// 保证新元素要么入队、要么如实返回失败，不会被静默丢弃
		}
		// 达到重试上限仍未入队：返回失败，由调用方计入丢弃
		return DropOutcome.ofRejected();
	}

	/**
	 * {@link #dropOldestAndOffer} 的结果。
	 *
	 * <p>不可变值对象，与 {@code DROP_OLDEST} 策略的记账语义对应：调用方无需再读队列状态，
	 * 即可判断本次是「入队且发生替换」「入队未替换」还是「未入队」。
	 *
	 * @param accepted  新元素是否成功入队
	 * @param discarded 被丢弃的最旧元素；没有发生替换时为 {@code null}
	 * @param <E>       元素类型
	 */
	public record DropOutcome<E>(boolean accepted, E discarded) {

		// 静态工厂方法不能命名为 accepted()/discarded()：record 已自动生成同名访问器，
		// 同名方法会导致编译失败（无效的访问器）
		/**
		 * 构造「已入队，未发生替换」的结果。
		 *
		 * @param <E> 元素类型
		 * @return 入队成功且无元素被丢弃的结果；{@code discarded} 为 {@code null}，
		 *         因此 {@link #replacedOldest()} 返回 {@code false}
		 */
		static <E> DropOutcome<E> ofAccepted() {
			return new DropOutcome<>(true, null);
		}

		/**
		 * 构造「已入队，且替换掉了最旧元素」的结果。
		 *
		 * @param discarded 被替换掉的最旧元素；不得为 {@code null}，否则语义与
		 *                  {@link #ofAccepted()} 重合
		 * @param <E>       元素类型
		 * @return 入队成功且发生替换的结果
		 */
		static <E> DropOutcome<E> ofReplaced(E discarded) {
			return new DropOutcome<>(true, discarded);
		}

		/**
		 * 构造「未入队」的结果。
		 *
		 * @param <E> 元素类型
		 * @return 入队失败的结果；调用方据此计入丢弃
		 */
		static <E> DropOutcome<E> ofRejected() {
			return new DropOutcome<>(false, null);
		}

		/**
		 * 是否发生了「丢弃最旧以腾位」的替换。
		 *
		 * @return 有最旧元素被丢弃时为 {@code true}
		 */
		public boolean replacedOldest() {
			return discarded != null;
		}
	}

	/**
	 * 取出队首元素。
	 *
	 * <p>非阻塞；多个消费者并发调用时每个元素只会被其中一个取出。
	 *
	 * @return 队首元素；队列为空时返回 {@code null}
	 */
	@SuppressWarnings("unchecked")
	public E poll() {
		while (true) {
			long current = consumed.get();
			int index = (int) (current & mask);
			long sequence = sequences[index];
			if (sequence != current) {
				return null;
			}
			if (!consumed.compareAndSet(current, current + 1)) {
				continue;
			}
			E element = (E) slots[index];
			slots[index] = null;
			sequences[index] = IDLE;
			return element;
		}
	}

	/**
	 * 阻塞取出，直到有元素或超时。
	 *
	 * <p>等待期间不检查中断状态：{@code parkNanos} 被中断时立即返回，循环随即再次尝试
	 * {@link #poll()}，因此本方法对中断不敏感，停机只能依赖调用方的超时或停止标志。
	 *
	 * @param timeoutNanos 最长等待纳秒数
	 * @return 队首元素；超时返回 {@code null}
	 */
	public E poll(long timeoutNanos) {
		long deadline = System.nanoTime() + timeoutNanos;
		while (true) {
			E element = poll();
			if (element != null) {
				return element;
			}
			if (System.nanoTime() >= deadline) {
				return null;
			}
			// 自旋 + 让步：审计消费端对延迟不敏感，避免在内核态频繁唤醒；
			// 中断只会让 parkNanos 提前返回，循环继续等待，不视为退出信号
			LockSupport.parkNanos(200_000L);
		}
	}

	/**
	 * 批量取出，最多 {@code maxBatchSize} 条。
	 *
	 * @param maxBatchSize 本次取出的数量上限
	 * @return 新分配的可变列表，由调用方持有；队列为空时立即返回空列表
	 */
	public List<E> pollBatch(int maxBatchSize) {
		List<E> batch = new ArrayList<>(Math.min(maxBatchSize, 64));
		for (int index = 0; index < maxBatchSize; index++) {
			E element = poll();
			if (element == null) {
				break;
			}
			batch.add(element);
		}
		return batch;
	}

	/**
	 * 清空队列。
	 *
	 * <p>用于停机排空或失败兜底。若仍有生产者并发入队，返回后队列可能又非空。
	 *
	 * @return 本次被清空的元素，按出队顺序排列；队列本已为空时返回空列表
	 */
	public List<E> drainAll() {
		List<E> drained = new ArrayList<>();
		E element;
		while ((element = poll()) != null) {
			drained.add(element);
		}
		return drained;
	}

	/**
	 * 生产序号。
	 *
	 * @return 已入队总数，含已被消费的元素
	 */
	public long producedCount() {
		return produced.get();
	}

	/**
	 * 消费序号。
	 *
	 * @return 已出队总数；与 {@link #producedCount()} 的差值即当前积压量
	 */
	public long consumedCount() {
		return consumed.get();
	}
}
