package io.github.pkyit.auditlog.core.chain;

import io.github.pkyit.auditlog.core.api.AuditChain;
import io.github.pkyit.auditlog.core.api.AuditChainStore;
import io.github.pkyit.auditlog.core.api.AuditFailureHandler;
import io.github.pkyit.auditlog.core.api.AuditMetrics;
import io.github.pkyit.auditlog.core.api.ChainState;
import io.github.pkyit.auditlog.core.metrics.SimpleAuditMetrics;
import io.github.pkyit.auditlog.core.model.AuditRecord;
import io.github.pkyit.auditlog.core.pipeline.AuditPipeline;
import io.github.pkyit.auditlog.core.pipeline.BackpressureStrategy;
import io.github.pkyit.auditlog.core.store.InMemoryAuditStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 哈希链跨重启连续性的回归测试。
 *
 * <p>链头 {@code head} 仅存在于内存，其跨重启的连续性依赖外部持久化的链状态：启动时若未恢复链头，
 * 新记录的 {@code prevHash} 与存储中上一条 {@code recordHash} 无法衔接，跨重启的链校验会在
 * 重启边界报告"被篡改"，使真实的篡改告警淹没在误报中。
 *
 * <p>覆盖三条恢复路径：状态文件可用、状态文件丢失但存储仍保留记录、无任何历史时的冷启动。
 */
class ChainPersistenceTest {

	private static final String SECRET = "chain-persistence-test-secret";

	private AuditRecord record(long seq) {
		return new AuditRecord.Builder()
			.id("rec-" + seq)
			.seq(seq)
			.operation("操作" + seq)
			.resource("user", String.valueOf(seq))
			.build();
	}

	private AuditPipeline newPipeline(Path stateFile, io.github.pkyit.auditlog.core.api.AuditStore store,
			AuditMetrics metrics, AuditChain chain) {
		// 参数顺序：queueCapacity, batchSize, batchTimeoutMillis, consumerThreads, strategy,
		//           blockTimeoutMillis, maxRetries, retryBackoffMillis, chainEnabled, recentCacheSize
		AuditPipeline.Config config = new AuditPipeline.Config(
			256, 8, 50L, 1, BackpressureStrategy.SPILL, 1000L, 0, 10L, true, 64);
		AuditPipeline pipeline = new AuditPipeline(config, store, chain, metrics,
			AuditFailureHandler.logging(), null, new FileChainStateStore(stateFile));
		pipeline.start();
		return pipeline;
	}

	private void waitUntil(BooleanSupplier condition, long timeoutMillis) {
		long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(20L);
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private ChainState awaitPersisted(AuditChainStore store, long expectedSeq) {
		long deadline = System.nanoTime() + 15_000L * 1_000_000L;
		while (System.nanoTime() < deadline) {
			ChainState state = store.load();
			if (state != null && state.lastDeliveredSeq() >= expectedSeq) {
				return state;
			}
			try {
				Thread.sleep(20L);
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return store.load();
	}

	@Test
	@DisplayName("跨重启链连续：第二批记录接在重启前最后一条的 recordHash 上")
	void chainContinuesAcrossRestart(@TempDir Path tempDir) throws Exception {
		Path stateFile = tempDir.resolve("chain-state.properties");
		FileChainStateStore store = new FileChainStateStore(stateFile);

		// 第一次运行：产生 5 条记录
		InMemoryAuditStore first = new InMemoryAuditStore();
		AuditPipeline firstPipeline = newPipeline(stateFile, first, new SimpleAuditMetrics(),
			new HmacAuditChain(SECRET));
		try {
			for (long seq = 1; seq <= 5; seq++) {
				firstPipeline.submit(record(seq));
			}
			waitUntil(() -> first.size() >= 5, 15_000L);
			assertEquals(5, first.size(), "第一次运行应落库 5 条");
		}
		finally {
			firstPipeline.stop();
		}

		ChainState persisted = awaitPersisted(store, 5L);
		assertNotNull(persisted, "第一批投递后必须持久化链状态");
		assertEquals(5L, persisted.lastDeliveredSeq());
		assertNotNull(persisted.head(), "链头必须被持久化");
		String headAfterFirstRun = first.getRecords().get(4).getRecordHash();
		assertEquals(headAfterFirstRun, persisted.head(), "持久化的链头应等于最后一条记录的 recordHash");

		// 第二次运行：模拟重启，链头从持久化状态恢复
		InMemoryAuditStore second = new InMemoryAuditStore();
		AuditPipeline secondPipeline = newPipeline(stateFile, second, new SimpleAuditMetrics(),
			new HmacAuditChain(SECRET));
		try {
			for (long seq = 6; seq <= 8; seq++) {
				secondPipeline.submit(record(seq));
			}
			waitUntil(() -> second.size() >= 3, 15_000L);
			assertEquals(3, second.size(), "第二次运行应落库 3 条");

			AuditRecord firstOfSecondRun = second.getRecords().get(0);
			assertEquals(headAfterFirstRun, firstOfSecondRun.getPrevHash(),
				"重启后第一条记录的 prevHash 必须等于重启前最后一条的 recordHash —— "
					+ "否则跨重启链校验必然报'被篡改'");
		}
		finally {
			secondPipeline.stop();
		}

		// 合并两次运行的记录，链必须整体自洽
		List<AuditRecord> all = new ArrayList<>(first.getRecords());
		all.addAll(second.getRecords());
		all.sort(java.util.Comparator.comparingLong(AuditRecord::getSeq));

		for (AuditRecord rec : all) {
			String expected = Hasher.hmacSha256(SECRET, rec.getPrevHash() + '|' + rec.canonicalContent());
			assertEquals(expected, rec.getRecordHash(),
				"seq=" + rec.getSeq() + " 的链哈希与内容不自洽");
		}
		AuditChain.ChainVerification verification = new HmacAuditChain(SECRET).verify(all);
		assertTrue(verification.valid(),
			"跨重启的完整链必须校验通过，失败原因：" + verification.reason());
		assertEquals(8, verification.verifiedCount());
	}

	@Test
	@DisplayName("seq 不回退：重启后新记录的 seq 接着历史最大值继续")
	void sequenceDoesNotRestartFromOne(@TempDir Path tempDir) throws Exception {
		Path stateFile = tempDir.resolve("chain-state.properties");
		FileChainStateStore store = new FileChainStateStore(stateFile);

		InMemoryAuditStore first = new InMemoryAuditStore();
		AuditPipeline firstPipeline = newPipeline(stateFile, first, new SimpleAuditMetrics(),
			new HmacAuditChain(SECRET));
		try {
			for (long seq = 1; seq <= 4; seq++) {
				firstPipeline.submit(record(seq));
			}
			waitUntil(() -> first.size() >= 4, 15_000L);
		}
		finally {
			firstPipeline.stop();
		}
		assertEquals(4L, awaitPersisted(store, 4L).lastDeliveredSeq());

		// 重启后提交未带 seq 的记录：框架应自动分配且必须大于 4
		InMemoryAuditStore second = new InMemoryAuditStore();
		AuditPipeline secondPipeline = newPipeline(stateFile, second, new SimpleAuditMetrics(),
			new HmacAuditChain(SECRET));
		try {
			boolean accepted = secondPipeline.submit(
				new AuditRecord.Builder().id("auto-seq").operation("自动序号").build());
			assertTrue(accepted, "提交应被接受");
			waitUntil(() -> second.size() >= 1, 15_000L);
			assertEquals(1, second.size());
			assertTrue(second.getRecords().get(0).getSeq() > 4L,
				"重启后自动分配的 seq 必须大于历史最大值（否则链上出现重复序号），实际="
					+ second.getRecords().get(0).getSeq());
		}
		finally {
			secondPipeline.stop();
		}
	}

	@Test
	@DisplayName("状态文件丢失时从存储兜底恢复链尾（模拟存储持久，只是状态文件被误删）")
	void recoversFromStoreWhenStateFileMissing(@TempDir Path tempDir) throws Exception {
		Path stateFile = tempDir.resolve("chain-state.properties");

		// 同一个 store 实例贯穿两次运行：它代表持久化存储，兜底恢复的前提是记录仍在存储中。
		// 若第二次运行改用全新的空 store，等于状态文件与存储同时丢失，该恢复路径无法被验证。
		InMemoryAuditStore durableStore = new InMemoryAuditStore();

		AuditPipeline firstPipeline = newPipeline(stateFile, durableStore, new SimpleAuditMetrics(),
			new HmacAuditChain(SECRET));
		try {
			for (long seq = 1; seq <= 3; seq++) {
				firstPipeline.submit(record(seq));
			}
			waitUntil(() -> durableStore.size() >= 3, 15_000L);
			assertEquals(3, durableStore.size());
		}
		finally {
			firstPipeline.stop();
		}
		String expectedHead = durableStore.getRecords().get(2).getRecordHash();

		// 模拟"状态文件被误删"，但存储里的记录仍在
		java.nio.file.Files.deleteIfExists(stateFile);
		assertNull(new FileChainStateStore(stateFile).load(), "状态文件已删除");
		assertNotNull(durableStore.recoverChainState(), "存储侧应能回读出链尾");

		// 第二次运行：状态文件没了，应退回"从存储回读链尾"
		AuditPipeline secondPipeline = newPipeline(stateFile, durableStore, new SimpleAuditMetrics(),
			new HmacAuditChain(SECRET));
		try {
			secondPipeline.submit(record(4));
			waitUntil(() -> durableStore.size() >= 4, 15_000L);
			assertEquals(4, durableStore.size());
			AuditRecord fourth = durableStore.getRecords().get(3);
			assertEquals(expectedHead, fourth.getPrevHash(),
				"状态文件丢失时应从存储回读链尾恢复，链不能断（实际 prevHash="
					+ fourth.getPrevHash() + "）");
		}
		finally {
			secondPipeline.stop();
		}
	}

	@Test
	@DisplayName("冷启动：没有任何历史时从 GENESIS 开始，且不误报")
	void coldStartBeginsAtGenesis(@TempDir Path tempDir) throws Exception {
		Path stateFile = tempDir.resolve("chain-state.properties");
		InMemoryAuditStore store = new InMemoryAuditStore();
		AuditPipeline pipeline = newPipeline(stateFile, store, new SimpleAuditMetrics(),
			new HmacAuditChain(SECRET));
		try {
			pipeline.submit(record(1));
			waitUntil(() -> store.size() >= 1, 15_000L);
			assertEquals("GENESIS", store.getRecords().get(0).getPrevHash(),
				"冷启动的第一条记录应从 GENESIS 起链");
		}
		finally {
			pipeline.stop();
		}
	}

	@Test
	@DisplayName("状态文件写入是原子的：不会读到半个文件")
	void stateFileWriteIsAtomic(@TempDir Path tempDir) throws Exception {
		Path stateFile = tempDir.resolve("chain-state.properties");
		FileChainStateStore store = new FileChainStateStore(stateFile);
		store.save(new ChainState("abc123", 42L));

		ChainState loaded = store.load();
		assertNotNull(loaded);
		assertEquals("abc123", loaded.head());
		assertEquals(42L, loaded.lastDeliveredSeq());
		assertFalse(loaded.isPending());

		// 覆盖写之后仍能正确读取（验证临时文件替换路径）
		store.save(new ChainState("def456", 99L));
		assertEquals("def456", store.load().head());
		assertEquals(99L, store.load().lastDeliveredSeq());

		// 不残留临时文件
		assertFalse(java.nio.file.Files.exists(stateFile.resolveSibling(
			stateFile.getFileName() + ".tmp")), "不应残留临时文件");
	}
}
