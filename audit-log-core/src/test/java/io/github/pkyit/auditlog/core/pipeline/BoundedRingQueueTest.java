package io.github.pkyit.auditlog.core.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BoundedRingQueue} 的并发与背压语义测试。
 *
 * <p>队列是「不丢数据」不变量的第一道闸门，其契约有三条：容量必须有界，否则无界增长会导致 OOM；
 * 队列满时 {@code offer} 必须返回 false 而非静默阻塞或覆盖已有元素，以便上层执行背压策略；
 * 多生产者多消费者下不得出现元素丢失或重复。
 */
class BoundedRingQueueTest {

	@Test
	@DisplayName("容量向上取整为 2 的幂")
	void normalizesCapacityToPowerOfTwo() {
		assertEquals(4, new BoundedRingQueue<>(3).capacity());
		assertEquals(8, new BoundedRingQueue<>(5).capacity());
		assertEquals(2, new BoundedRingQueue<>(2).capacity());
	}

	@Test
	@DisplayName("先进先出")
	void respectsFifoOrder() {
		BoundedRingQueue<String> queue = new BoundedRingQueue<>(4);
		queue.offer("a");
		queue.offer("b");
		queue.offer("c");
		assertEquals("a", queue.poll());
		assertEquals("b", queue.poll());
		assertEquals("c", queue.poll());
		assertNull(queue.poll());
	}

	@Test
	@DisplayName("队列满时返回 false，而不是阻塞或覆盖已有元素")
	void rejectsWhenFull() {
		BoundedRingQueue<String> queue = new BoundedRingQueue<>(2);
		assertTrue(queue.offer("a"));
		assertTrue(queue.offer("b"));
		assertFalse(queue.offer("c"), "满时必须明确拒绝，让上层执行背压策略");
		assertEquals(2, queue.size());
		assertEquals("a", queue.poll(), "被拒绝的入队不得破坏已有元素");
	}

	@Test
	@DisplayName("DROP_OLDEST 语义：覆盖最旧元素，并如实报告是否发生了替换")
	void dropOldestReturnsDiscardedElement() {
		BoundedRingQueue<String> queue = new BoundedRingQueue<>(2);
		queue.offer("a");
		queue.offer("b");
		BoundedRingQueue.DropOutcome<String> outcome = queue.dropOldestAndOffer("c");
		assertTrue(outcome.accepted(), "新元素必须入队");
		assertTrue(outcome.replacedOldest(), "应发生过替换");
		assertEquals("a", outcome.discarded(), "应返回被丢弃的最旧元素供计数与告警");
		assertEquals(2, queue.size());
		assertEquals("b", queue.poll());
		assertEquals("c", queue.poll());
	}

	@Test
	@DisplayName("回归：队列未满时 dropOldestAndOffer 不丢弃任何元素（早期实现会误计丢弃）")
	void dropOldestDoesNotDiscardWhenQueueHasRoom() {
		BoundedRingQueue<String> queue = new BoundedRingQueue<>(4);
		BoundedRingQueue.DropOutcome<String> outcome = queue.dropOldestAndOffer("only");
		assertTrue(outcome.accepted());
		assertFalse(outcome.replacedOldest(), "队列没满就不该丢弃任何元素");
		assertNull(outcome.discarded());
		assertEquals(java.util.List.of("only"), queue.drainAll());
	}

	@Test
	@DisplayName("pollBatch 返回不超过上限的批次，空队列立即返回")
	void pollsBatches() {
		BoundedRingQueue<String> queue = new BoundedRingQueue<>(8);
		for (int index = 0; index < 5; index++) {
			queue.offer("v" + index);
		}
		List<String> batch = queue.pollBatch(10);
		assertEquals(5, batch.size());
		assertTrue(queue.isEmpty());
		assertTrue(queue.pollBatch(10).isEmpty());
	}

	@Test
	@DisplayName("drainAll 清空队列并返回全部元素")
	void drainsAll() {
		BoundedRingQueue<String> queue = new BoundedRingQueue<>(8);
		queue.offer("a");
		queue.offer("b");
		assertEquals(List.of("a", "b"), queue.drainAll());
		assertTrue(queue.isEmpty());
	}

	@Test
	@DisplayName("多生产者多消费者：不丢、不重、不越界")
	void isCorrectUnderConcurrency() throws Exception {
		int producers = 4;
		int consumers = 4;
		int perProducer = 2_000;
		int total = producers * perProducer;

		BoundedRingQueue<Integer> queue = new BoundedRingQueue<>(256);
		Set<Integer> received = java.util.Collections.synchronizedSet(new HashSet<>());
		AtomicInteger duplicates = new AtomicInteger();
		CountDownLatch startGate = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(producers + consumers);
		ExecutorService pool = Executors.newFixedThreadPool(producers + consumers);

		for (int producerIndex = 0; producerIndex < producers; producerIndex++) {
			int base = producerIndex * perProducer;
			pool.submit(() -> {
				try {
					startGate.await();
					for (int index = 0; index < perProducer; index++) {
						int value = base + index;
						// 队列有界：满时自旋重试（模拟 BLOCK 策略），确保最终全部入队
						while (!queue.offer(value)) {
							Thread.onSpinWait();
						}
					}
				}
				catch (InterruptedException error) {
					Thread.currentThread().interrupt();
				}
				finally {
					done.countDown();
				}
			});
		}
		for (int consumerIndex = 0; consumerIndex < consumers; consumerIndex++) {
			pool.submit(() -> {
				try {
					startGate.await();
					while (received.size() < total) {
						Integer value = queue.poll(200_000_000L);
						if (value == null) {
							// 生产已结束且队列取空则退出
							if (queue.isEmpty()) {
								break;
							}
							continue;
						}
						if (!received.add(value)) {
							duplicates.incrementAndGet();
						}
					}
				}
				catch (InterruptedException error) {
					Thread.currentThread().interrupt();
				}
				finally {
					done.countDown();
				}
			});
		}

		startGate.countDown();
		assertTrue(done.await(60, TimeUnit.SECONDS), "并发测试应在超时前完成");
		pool.shutdownNow();

		assertEquals(0, duplicates.get(), "不得出现重复元素");
		assertEquals(total, received.size(), "不得丢失元素");
	}

	@Test
	@DisplayName("poll(timeout) 超时返回 null，而不是永久阻塞")
	void pollTimesOut() {
		BoundedRingQueue<String> queue = new BoundedRingQueue<>(4);
		long start = System.nanoTime();
		assertNull(queue.poll(50_000_000L));
		long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
		assertTrue(elapsedMillis >= 40L, "应在超时后才返回，实际 " + elapsedMillis + "ms");
		queue.offer("late");
		assertNotNull(queue.poll(10_000_000L), "入队后应能立即取出");
	}
}
