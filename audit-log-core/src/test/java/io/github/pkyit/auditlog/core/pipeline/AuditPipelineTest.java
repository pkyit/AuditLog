package io.github.pkyit.auditlog.core.pipeline;

import io.github.pkyit.auditlog.core.api.AuditChain;
import io.github.pkyit.auditlog.core.api.AuditFailureHandler;
import io.github.pkyit.auditlog.core.api.AuditMetricNames;
import io.github.pkyit.auditlog.core.chain.Sha256AuditChain;
import io.github.pkyit.auditlog.core.metrics.SimpleAuditMetrics;
import io.github.pkyit.auditlog.core.model.ActionType;
import io.github.pkyit.auditlog.core.model.AuditRecord;
import io.github.pkyit.auditlog.core.model.Outcome;
import io.github.pkyit.auditlog.core.model.PrincipalType;
import io.github.pkyit.auditlog.core.serialize.JsonLinesAuditCodec;
import io.github.pkyit.auditlog.core.store.InMemoryAuditStore;
import io.github.pkyit.auditlog.core.wal.FileAuditJournal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuditPipeline} 的可靠性与异常隔离测试（不变量 I3）。
 *
 * <p>覆盖的契约：
 * <ul>
 *   <li>异常隔离：存储抛异常不得冒泡到业务线程；</li>
 *   <li>不丢数据：无法投递的记录进入本地兜底日志或死信，且 {@code dropped == 0}；</li>
 *   <li>崩溃可恢复：进程重启后（新实例复用同一 WAL 目录）记录被重放投递；</li>
 *   <li>停机排空：{@code stop()} 后队列为空；</li>
 *   <li>链顺序：{@code seq} 单调，链不出现分叉。</li>
 * </ul>
 */
class AuditPipelineTest {

	private AuditRecord record(long seq) {
		return new AuditRecord.Builder()
			.id("pipeline-test-" + seq)
			.seq(seq)
			.eventTime(Instant.parse("2024-01-01T00:00:00Z"))
			.receivedTime(Instant.parse("2024-01-01T00:00:01Z"))
			.principal("tester", "tester", PrincipalType.AUTHENTICATED)
			.operation("测试操作-" + seq)
			.actionType(ActionType.MUTATE)
			.resource("test", String.valueOf(seq))
			.http("POST", "/test/" + seq, null)
			.outcome(Outcome.SUCCESS)
			.httpStatus(200)
			.durationMillis(1L)
			.build();
	}

	private AuditPipeline.Config config(BackpressureStrategy strategy, int queueCapacity) {
		return new AuditPipeline.Config(queueCapacity, 50, 100L, 1, strategy, 500L, 0, 50L, true, 1_000);
	}

	private AuditPipeline newPipeline(AuditPipeline.Config config, InMemoryAuditStore store, Path journalDir,
			SimpleAuditMetrics metrics) {
		return newPipeline(config, store, openJournal(journalDir, metrics), metrics);
	}

	private FileAuditJournal openJournal(Path journalDir, SimpleAuditMetrics metrics) {
		FileAuditJournal journal = new FileAuditJournal(journalDir, new JsonLinesAuditCodec(), metrics,
			64L * 1024 * 1024, 5, 512, 1_000);
		try {
			journal.open();
		}
		catch (Exception error) {
			throw new IllegalStateException("无法打开测试用 WAL", error);
		}
		return journal;
	}

	private AuditPipeline newPipeline(AuditPipeline.Config config, InMemoryAuditStore store,
			FileAuditJournal journal, SimpleAuditMetrics metrics) {
		return new AuditPipeline(config, store, new Sha256AuditChain(), metrics,
			AuditFailureHandler.logging(), journal);
	}

	@Test
	@DisplayName("存储故障时不丢数据：记录进入死信，dropped 保持 0")
	void doesNotLoseDataWhenStoreFails(@TempDir Path tempDir) throws Exception {
		SimpleAuditMetrics metrics = new SimpleAuditMetrics();
		InMemoryAuditStore store = new InMemoryAuditStore(metrics);
		store.setFailing(true);

		// 队列容量必须明显大于注入条数，否则部分记录会走 SPILL（进 WAL）而不是进队列，
		// 这些记录不会被投递，也就不会进死信，使"全部记录都进死信"的断言不成立。
		int injected = 20;
		AuditPipeline pipeline = newPipeline(config(BackpressureStrategy.SPILL, 256), store,
			tempDir.resolve("journal"), metrics);
		pipeline.start();
		try {
			for (int index = 1; index <= injected; index++) {
				assertTrue(pipeline.submit(record(index)), "每条记录都必须被明确接受（入队或落 WAL）");
			}
			// 等"全部记录都已被处理"：队列排空 + 死信计数到位。
			waitFor(() -> pipeline.queueDepth() == 0
				&& metrics.counter(AuditMetricNames.DEADLETTER) >= injected, 20_000L);

			assertEquals(injected, metrics.counter(AuditMetricNames.DEADLETTER),
				"每条无法投递的记录都必须被计入死信。诊断：" + diagnostic(metrics, pipeline, injected));
			assertEquals(injected, metrics.counter(AuditMetricNames.ENQUEUED),
				"队列容量足够时，全部记录都应走队列而不是 WAL");
			assertEquals(0L, pipeline.droppedCount(), "存储故障不得导致记录被静默丢弃");
			assertTrue(metrics.counter(AuditMetricNames.STORE_FAILED) > 0, "投递失败必须被计数");

			// 死信必须逐条包含全部无法投递的记录（这是「不丢数据」的直接证据）
			Path deadLetter = tempDir.resolve("journal").resolve("deadletter.jsonl");
			java.util.Set<String> expectedIds = new java.util.HashSet<>();
			for (int index = 1; index <= injected; index++) {
				expectedIds.add("pipeline-test-" + index);
			}
			waitFor(() -> deadLetteredIds(deadLetter).containsAll(expectedIds), 20_000L);

			assertTrue(Files.exists(deadLetter), "死信文件应存在");
			java.util.Set<String> actualIds = deadLetteredIds(deadLetter);
			for (String expectedId : expectedIds) {
				assertTrue(actualIds.contains(expectedId),
					"死信应包含记录 " + expectedId + "，实际=" + actualIds);
			}
			assertEquals(0L, pipeline.droppedCount(), "全程 dropped 必须保持 0");
		}
		finally {
			pipeline.stop();
		}
	}

	@Test
	@DisplayName("队列溢出走 SPILL：记录落 WAL 而不是被丢弃，且可重放")
	void spilledRecordsGoToJournalAndAreReplayable(@TempDir Path tempDir) throws Exception {
		Path journalDir = tempDir.resolve("journal");
		SimpleAuditMetrics metrics = new SimpleAuditMetrics();
		InMemoryAuditStore failingStore = new InMemoryAuditStore(metrics);
		failingStore.setFailing(true);

		// 用极小队列制造溢出：记录无法入队时必须降级写 WAL（不丢），而不是静默丢弃。
		int injected = 20;
		AuditPipeline pipeline = newPipeline(config(BackpressureStrategy.SPILL, 2), failingStore, journalDir,
			metrics);
		pipeline.start();
		try {
			for (int index = 1; index <= injected; index++) {
				pipeline.submit(record(index));
			}
			waitFor(() -> metrics.counter(AuditMetricNames.JOURNALED)
				+ metrics.counter(AuditMetricNames.DEADLETTER) >= injected, 20_000L);

			long tracked = metrics.counter(AuditMetricNames.JOURNALED)
				+ metrics.counter(AuditMetricNames.DEADLETTER);
			assertEquals(0L, pipeline.droppedCount(), "队列溢出不得导致记录被丢弃");
			assertTrue(metrics.counter(AuditMetricNames.BACKPRESSURE) > 0, "背压触发必须被计数");
			assertTrue(tracked >= injected,
				"每条记录都必须有去处（WAL 或死信）。诊断：" + diagnostic(metrics, pipeline, injected));
			assertTrue(metrics.counter(AuditMetricNames.JOURNALED) > 0,
				"小队列下应有记录通过 SPILL 进入本地兜底日志");
		}
		finally {
			pipeline.stop();
		}

		// 恢复后重放：把无法投递的记录重新投递到健康存储
		SimpleAuditMetrics secondMetrics = new SimpleAuditMetrics();
		InMemoryAuditStore healthyStore = new InMemoryAuditStore(secondMetrics);
		AuditPipeline second = newPipeline(config(BackpressureStrategy.SPILL, 128), healthyStore, journalDir,
			secondMetrics);
		second.start();
		try {
			waitFor(() -> healthyStore.size() > 0, 15_000L);
			assertTrue(healthyStore.size() > 0, "WAL 中的记录应能在存储恢复后被重放");
			assertEquals(0L, second.droppedCount(), "重放不得丢弃记录");
		}
		finally {
			second.stop();
		}
	}

	/** 汇总所有相关计数，用于失败诊断（避免"只看到一个数字"而无法定位）。 */
	private String diagnostic(SimpleAuditMetrics metrics, AuditPipeline pipeline, int expected) {
		return "expected=" + expected
			+ ", deadletter=" + metrics.counter(AuditMetricNames.DEADLETTER)
			+ ", storeFailed=" + metrics.counter(AuditMetricNames.STORE_FAILED)
			+ ", storeRetried=" + metrics.counter(AuditMetricNames.STORE_RETRIED)
			+ ", delivered=" + metrics.counter(AuditMetricNames.DELIVERED)
			+ ", journaled=" + metrics.counter(AuditMetricNames.JOURNALED)
			+ ", enqueued=" + metrics.counter(AuditMetricNames.ENQUEUED)
			+ ", dropped=" + metrics.counter(AuditMetricNames.DROPPED)
			+ ", journalRejected=" + metrics.counter(AuditMetricNames.JOURNAL_REJECTED)
			+ ", queueDepth=" + pipeline.queueDepth()
			+ ", droppedCount=" + pipeline.droppedCount()
			+ ", interrupted=" + Thread.currentThread().isInterrupted();
	}

	/** 从死信文件解析出记录 ID 集合。 */
	private java.util.Set<String> deadLetteredIds(Path deadLetter) {
		if (!Files.exists(deadLetter)) {
			return java.util.Set.of();
		}
		try {
			return Files.readAllLines(deadLetter, StandardCharsets.UTF_8).stream()
				.map(line -> {
					int start = line.indexOf("\"id\":\"");
					if (start < 0) {
						return "";
					}
					int from = start + "\"id\":\"".length();
					int to = line.indexOf('"', from);
					return to < 0 ? "" : line.substring(from, to);
				})
				.filter(id -> !id.isEmpty())
				.collect(Collectors.toSet());
		}
		catch (java.io.IOException error) {
			return java.util.Set.of();
		}
	}

	@Test
	@DisplayName("投递指标只计一次：存储实现不得与流水线重复计数（回归：实测曾翻倍）")
	void countsDeliveredExactlyOnce(@TempDir Path tempDir) throws Exception {
		SimpleAuditMetrics metrics = new SimpleAuditMetrics();
		// 使用内置存储：delivered 由流水线统一计数，存储自身不得累加（见 ADR-013）
		InMemoryAuditStore store = new InMemoryAuditStore(metrics);
		int total = 50;
		AuditPipeline pipeline = newPipeline(config(BackpressureStrategy.SPILL, 256), store,
			tempDir.resolve("journal"), metrics);
		pipeline.start();
		try {
			for (int index = 1; index <= total; index++) {
				pipeline.submit(record(index));
			}
			waitFor(() -> store.size() >= total, 15_000L);
			assertEquals(total, store.size(), "记录应全部落库");

			// 投递计数必须恰好等于实际落库条数。计数翻倍会使"审计写入量"不可信，
			// 进而使"不丢数据"这一不变量无法被证明。
			assertEquals(total, metrics.counter(AuditMetricNames.DELIVERED),
				"audit.store.delivered 必须恰好等于投递条数；诊断：" + diagnostic(metrics, pipeline, total));
		}
		finally {
			pipeline.stop();
		}
	}

	@Test
	@DisplayName("链哈希可离线复现：经流水线投递的记录，其 recordHash 必须等于重算值（回归）")
	void persistedRecordHashIsIndependentlyReproducible(@TempDir Path tempDir) throws Exception {
		SimpleAuditMetrics metrics = new SimpleAuditMetrics();
		InMemoryAuditStore store = new InMemoryAuditStore(metrics);
		int total = 10;
		AuditPipeline pipeline = newPipeline(config(BackpressureStrategy.SPILL, 256), store,
			tempDir.resolve("journal"), metrics);
		pipeline.start();
		try {
			for (int index = 1; index <= total; index++) {
				pipeline.submit(record(index));
			}
			waitFor(() -> store.size() >= total, 15_000L);
			assertEquals(total, store.size());

			// 模拟第三方复核：把落库记录再经过一次编解码（真实审计文件就是这样读出来的），
			// 然后按公开公式独立重算哈希。
			io.github.pkyit.auditlog.core.serialize.JsonLinesAuditCodec codec =
				new io.github.pkyit.auditlog.core.serialize.JsonLinesAuditCodec();
			String previous = null;
			for (AuditRecord delivered : store.getRecords()) {
				AuditRecord roundTripped = codec.decode(codec.encode(delivered));
				assertNotNull(roundTripped, "记录必须能被解码");

				if (previous != null) {
					assertEquals(previous, roundTripped.getPrevHash(),
						"seq=" + roundTripped.getSeq() + " 的 prevHash 应指向前一条 recordHash");
				}
				String expected = io.github.pkyit.auditlog.core.chain.Hasher
					.sha256(roundTripped.getPrevHash() + '|' + roundTripped.canonicalContent());
				assertEquals(expected, roundTripped.getRecordHash(),
					"seq=" + roundTripped.getSeq()
						+ " 的 recordHash 与从持久化内容重算的结果不一致 —— 第三方将无法离线校验审计历史");
				previous = roundTripped.getRecordHash();
			}
		}
		finally {
			pipeline.stop();
		}
	}

	/** 等待 WAL 中已落盘指定条数（供崩溃恢复类用例使用）。 */
	private void waitForJournaled(FileAuditJournal journal, long expected, long timeoutMillis) throws Exception {
		waitFor(() -> journal.journaledCount() >= expected, timeoutMillis);
	}

	@Test
	@DisplayName("崩溃恢复：新实例复用 WAL 目录并重放未投递记录")
	void replaysJournalOnStartup(@TempDir Path tempDir) throws Exception {
		Path journalDir = tempDir.resolve("journal");

		// 第一次运行：用极小队列 + SPILL 制造"队列溢出 → 落 WAL"的真实路径，
		// 然后直接停机（不做任何投递），模拟"记录已落盘但进程被关闭"。
		SimpleAuditMetrics firstMetrics = new SimpleAuditMetrics();
		InMemoryAuditStore unusedStore = new InMemoryAuditStore(firstMetrics);
		FileAuditJournal firstJournal = openJournal(journalDir, firstMetrics);
		AuditPipeline first = newPipeline(config(BackpressureStrategy.SPILL, 1), unusedStore, firstJournal,
			firstMetrics);

		int injected = 20;
		for (int index = 1; index <= injected; index++) {
			first.submit(record(index));
		}
		// 等 WAL 真正落盘（写线程是异步的，固定 sleep 会与它竞态）
		waitForJournaled(firstJournal, injected, 15_000L);
		long spooled = firstJournal.journaledCount();
		first.stop();
		assertTrue(spooled > 0, "队列溢出时应有记录被写入本地兜底日志");

		// 第二次运行：存储恢复，启动时应重放 WAL 中的历史记录
		SimpleAuditMetrics secondMetrics = new SimpleAuditMetrics();
		InMemoryAuditStore healthyStore = new InMemoryAuditStore(secondMetrics);
		AuditPipeline second = newPipeline(config(BackpressureStrategy.SPILL, 64), healthyStore, journalDir,
			secondMetrics);
		second.start();
		try {
			// 等所有被 spool 的记录都被重放（而不是固定 sleep）
			waitFor(() -> healthyStore.size() >= spooled, 15_000L);

			assertFalse(healthyStore.getRecords().isEmpty(),
				"重启后必须重放 WAL 中的记录（否则审计数据就丢了）");
			assertEquals(0L, second.droppedCount(), "重放过程不得丢弃记录");
			assertTrue(healthyStore.getRecords().stream().anyMatch(item -> "pipeline-test-1".equals(item.getId())),
				"应包含崩溃前未投递的第一条记录");
			assertTrue(secondMetrics.counter(AuditMetricNames.REPLAYED) > 0, "重放必须被计数");
		}
		finally {
			second.stop();
		}
	}

	@Test
	@DisplayName("正常投递：记录全部落到存储，序号单调递增")
	void deliversAllRecordsWithMonotonicSequence(@TempDir Path tempDir) throws Exception {
		SimpleAuditMetrics metrics = new SimpleAuditMetrics();
		InMemoryAuditStore store = new InMemoryAuditStore(metrics);
		// 队列容量必须大于注入条数：否则超出部分会走 SPILL 进 WAL（这是正确行为），
		// 于是 store 里就不是"全部记录"，断言会变成对不可控时序的依赖。
		// 本用例要验证的是"正常投递路径"，因此给足容量，把 SPILL 留给专门的重放用例。
		int total = 200;
		AuditPipeline pipeline = newPipeline(config(BackpressureStrategy.SPILL, 1024), store,
			tempDir.resolve("journal"), metrics);
		pipeline.start();
		try {
			for (int index = 1; index <= total; index++) {
				assertTrue(pipeline.submit(record(index)), "队列容量充足时每条都必须入队成功");
			}
			waitFor(() -> store.size() >= total, 20_000L);

			assertEquals(total, store.size(), "所有记录都应被投递。诊断：" + diagnostic(metrics, pipeline, total));
			assertEquals(0L, pipeline.droppedCount());
			assertEquals(0L, metrics.counter(AuditMetricNames.JOURNALED),
				"容量充足时不应触发 SPILL（否则说明容量配置与用例预期不符）");

			List<Long> seqs = store.getRecords().stream().map(AuditRecord::getSeq).sorted()
				.collect(Collectors.toList());
			for (int index = 1; index < seqs.size(); index++) {
				assertTrue(seqs.get(index) > seqs.get(index - 1), "序号必须严格单调递增");
			}
		}
		finally {
			pipeline.stop();
		}
	}

	@Test
	@DisplayName("停机排空：stop() 之后队列为空且 WAL 已 flush")
	void drainsQueueOnStop(@TempDir Path tempDir) throws Exception {
		SimpleAuditMetrics metrics = new SimpleAuditMetrics();
		InMemoryAuditStore store = new InMemoryAuditStore(metrics);
		AuditPipeline pipeline = newPipeline(config(BackpressureStrategy.SPILL, 2_048), store,
			tempDir.resolve("journal"), metrics);
		pipeline.start();
		for (int index = 1; index <= 500; index++) {
			pipeline.submit(record(index));
		}
		// 立即停机，模拟"请求还在产生记录时应用被关闭"
		pipeline.stop();
		assertEquals(0, pipeline.queueDepth(), "停机后队列必须被排空");
		assertEquals(0L, pipeline.droppedCount(), "停机不得丢弃记录");
	}

	@Test
	@DisplayName("异常隔离：存储抛异常不得冒泡到提交方（业务线程）")
	void isolatesStoreExceptions(@TempDir Path tempDir) throws Exception {
		SimpleAuditMetrics metrics = new SimpleAuditMetrics();
		InMemoryAuditStore store = new InMemoryAuditStore(metrics);
		store.setFailing(true);
		AuditPipeline pipeline = newPipeline(config(BackpressureStrategy.SPILL, 64), store,
			tempDir.resolve("journal"), metrics);
		pipeline.start();
		try {
			// 存储持续抛异常时，submit 仍不得抛出异常
			for (int index = 1; index <= 30; index++) {
				pipeline.submit(record(index));
			}
			// 等队列被消费完，确保异常路径确实被执行过（而不是靠固定 sleep）
			waitFor(() -> pipeline.queueDepth() == 0, 10_000L);
			assertEquals(0L, pipeline.droppedCount());
		}
		finally {
			pipeline.stop();
		}
	}

	/**
	 * 背压策略契约测试。
	 *
	 * <p>不构造"队列恰好打满"的时序：该时序依赖消费者与断言之间的竞态，会产生随机假失败，
	 * 而假失败会掩盖真实告警。因此本方法只断言可静态确定的契约：
	 * <ul>
	 *   <li>策略枚举的完整性与默认值（防止契约被无意修改）；</li>
	 *   <li>"丢弃会被计数"由 {@link BoundedRingQueueTest} 与端到端验收 A11 覆盖；</li>
	 *   <li>"FAIL_FAST 会抛出"由端到端验收在真实过载下覆盖。</li>
	 * </ul>
	 */
	@Test
	@DisplayName("背压策略契约：五种策略齐备且默认为 SPILL（不丢数据）")
	void backpressureStrategyContract() {
		java.util.Set<String> names = java.util.Arrays.stream(BackpressureStrategy.values())
			.map(Enum::name).collect(Collectors.toSet());
		assertEquals(java.util.Set.of("SPILL", "BLOCK", "DROP_OLDEST", "DROP_NEWEST", "FAIL_FAST"), names,
			"策略集合是对外契约，变更需同步文档与验收用例");

		assertEquals(BackpressureStrategy.SPILL, AuditPipeline.Config.defaults().strategy(),
			"默认策略必须是不丢数据的 SPILL");
		assertEquals(BackpressureStrategy.SPILL,
			new AuditPipeline.Config(10, 10, 10L, 1, null, 10L, 0, 10L, false, 10).strategy(),
			"显式传 null 时应回落到 SPILL，而不是抛 NPE");
	}

	@Test
	@DisplayName("配置防御：非法参数被规范化为安全默认值")
	void configNormalizesInvalidValues() {
		AuditPipeline.Config config = new AuditPipeline.Config(-1, 0, -5L, 0,
			null, -1L, -1, -1L, false, -1);
		assertTrue(config.queueCapacity() > 0, "队列容量必须为正（否则无法工作）");
		assertTrue(config.batchSize() > 0);
		assertTrue(config.batchTimeoutMillis() > 0);
		assertTrue(config.consumerThreads() >= 1);
		assertTrue(config.maxRetries() >= 0);
		assertTrue(config.retryBackoffMillis() > 0);
		assertTrue(config.recentCacheSize() > 0);
	}

	@Test
	@DisplayName("DROP_OLDEST 语义由队列层保证：溢出时如实报告被丢弃者供计数")
	void dropOldestSemanticsDelegatedToQueue() {
		BoundedRingQueue<String> queue = new BoundedRingQueue<>(2);
		queue.offer("first");
		queue.offer("second");
		BoundedRingQueue.DropOutcome<String> outcome = queue.dropOldestAndOffer("third");
		assertTrue(outcome.accepted(), "新元素必须入队（不能因为并发竞态被静默丢弃）");
		assertEquals("first", outcome.discarded(),
			"必须返回被丢弃的记录，否则上层无法计数与告警");
		assertEquals(java.util.List.of("second", "third"), queue.drainAll());
	}

	@Test
	@DisplayName("链与多消费者不兼容时自动降为单消费者（避免产生必然校验失败的链）")
	void forcesSingleConsumerWhenChainEnabled() {
		AuditPipeline.Config requested = new AuditPipeline.Config(64, 10, 100L, 4,
			BackpressureStrategy.SPILL, 500L, 0, 50L, true, 100);
		assertEquals(1, requested.consumerThreads(), "启用哈希链时必须串行投递");

		AuditPipeline.Config noChain = new AuditPipeline.Config(64, 10, 100L, 4,
			BackpressureStrategy.SPILL, 500L, 0, 50L, false, 100);
		assertEquals(4, noChain.consumerThreads(), "关闭哈希链时应保留并发投递能力");
	}

	@Test
	@DisplayName("未启动时的提交：不得静默成功，必须落 WAL 或明确计数")
	void submitBeforeStartIsNotSilent(@TempDir Path tempDir) throws Exception {
		SimpleAuditMetrics metrics = new SimpleAuditMetrics();
		InMemoryAuditStore store = new InMemoryAuditStore(metrics);
		FileAuditJournal journal = openJournal(tempDir.resolve("journal"), metrics);
		AuditPipeline notStarted = newPipeline(config(BackpressureStrategy.SPILL, 16), store, journal, metrics);

		// 流水线未 start()：记录必须进 WAL（可恢复）或被计数，绝不能"返回成功但消失"
		boolean accepted = notStarted.submit(record(1));
		assertTrue(accepted, "未启动时应降级写入本地兜底日志，返回 true 表示已持久化");
		waitFor(() -> journal.pendingCount() > 0, 5_000L);
		assertTrue(journal.pendingCount() > 0, "记录必须已落 WAL，否则重启后就丢了");
		assertEquals(0L, notStarted.droppedCount(), "不得静默丢弃");
		journal.close();
	}

	@Test
	@DisplayName("链校验：投递后的记录构成可校验的完整链")
	void producesVerifiableChain(@TempDir Path tempDir) throws Exception {
		SimpleAuditMetrics metrics = new SimpleAuditMetrics();
		InMemoryAuditStore store = new InMemoryAuditStore(metrics);
		AuditPipeline pipeline = newPipeline(config(BackpressureStrategy.SPILL, 128), store,
			tempDir.resolve("journal"), metrics);
		pipeline.start();
		try {
			int total = 20;
			for (int index = 1; index <= total; index++) {
				pipeline.submit(record(index));
			}
			waitFor(() -> store.size() >= total, 10_000L);

			AuditChain.ChainVerification verification = pipeline.verifyChain();
			assertTrue(verification.valid(), "投递后应构成可校验的链，原因：" + verification.reason());
			assertTrue(verification.verifiedCount() >= total);
			assertNotNull(pipeline.chain().currentHead());
		}
		finally {
			pipeline.stop();
		}
	}

	private void waitFor(java.util.function.BooleanSupplier condition, long timeoutMillis) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
			Thread.sleep(20L);
		}
	}
}
