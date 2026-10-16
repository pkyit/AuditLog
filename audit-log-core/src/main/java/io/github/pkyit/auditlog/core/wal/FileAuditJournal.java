package io.github.pkyit.auditlog.core.wal;

import io.github.pkyit.auditlog.core.api.AuditCodec;
import io.github.pkyit.auditlog.core.api.AuditJournal;
import io.github.pkyit.auditlog.core.api.AuditMetricNames;
import io.github.pkyit.auditlog.core.api.AuditMetrics;
import io.github.pkyit.auditlog.core.model.AuditRecord;
import io.github.pkyit.auditlog.core.pipeline.BoundedRingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 基于本地文件的兜底日志（WAL）。
 *
 * <p>{@link #append} 不得在业务线程上做磁盘 I/O：它只把记录放入独立的有界队列，
 * 由专属写线程落盘。SPILL 策略的职责是保护业务 SLA，若 append 自身成为阻塞点，
 * 相当于用一个瓶颈替换另一个瓶颈。
 *
 * <p>降级层级（每一级都有独立指标，绝不静默）：
 * <ol>
 *   <li>入 WAL 队列成功 → 由写线程落盘（{@code audit.pipeline.journaled}）；</li>
 *   <li>WAL 队列也满 → 写入线程本地的内存缓冲（{@code audit.pipeline.journal.rejected}），
 *       写线程下一轮先排空内存缓冲，因此数据仍有机会落盘；</li>
 *   <li>内存缓冲达到上限 → 真正丢弃并计数 + 触发失败告警（这是最后一道防线，必须人工介入）。</li>
 * </ol>
 *
 * <p>文件布局（追加写、一条一行、崩溃最多损坏最后一行）：
 * <pre>
 * {dir}/journal-00001.jsonl   待重放
 * {dir}/journal-00002.jsonl
 * {dir}/deadletter.jsonl      重试耗尽的记录
 * {dir}/journal.ckpt          已重放到的 seq
 * </pre>
 */
public class FileAuditJournal implements AuditJournal {

	private static final Logger log = LoggerFactory.getLogger(FileAuditJournal.class);

	private static final String JOURNAL_PREFIX = "journal-";
	private static final String JOURNAL_SUFFIX = ".jsonl";
	private static final String DEAD_LETTER = "deadletter.jsonl";
	private static final String CHECKPOINT = "journal.ckpt";

	private final Path directory;
	private final AuditCodec codec;
	private final AuditMetrics metrics;
	private final long maxFileBytes;
	private final int maxHistory;

	private final BoundedRingQueue<AuditRecord> writeQueue;
	private final List<AuditRecord> memoryBuffer = new ArrayList<>();
	private final int memoryBufferLimit;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicLong checkpoint = new AtomicLong(0L);
	private final AtomicLong journaled = new AtomicLong(0L);
	private final AtomicLong rejected = new AtomicLong(0L);
	private final AtomicLong dropped = new AtomicLong(0L);

	/**
	 * 当前所有 WAL 分段里"已落盘的有效行数"。
	 *
	 * <p>该计数器使 {@link #pendingCount()} 成为纯内存读。pendingCount 位于采集热路径
	 * （每次成功入队都会被调用），不得做磁盘 I/O；更新点只有启动扫描、写线程追加、
	 * 保留策略删除分段三处，均为低频操作。
	 */
	private final java.util.concurrent.atomic.LongAdder onDiskLines =
		new java.util.concurrent.atomic.LongAdder();

	/** 已计入 {@link #onDiskLines} 的片段及其行数（供删除时精确扣减）。 */
	private final Map<Path, Long> countedSegments = new java.util.concurrent.ConcurrentHashMap<>();

	private Thread writer;
	private BufferedWriter writerHandle;
	private long currentFileBytes;
	private int currentSegment = 1;

	public FileAuditJournal(Path directory, AuditCodec codec, AuditMetrics metrics,
			long maxFileBytes, int maxHistory, int queueCapacity, int memoryBufferLimit) {
		this.directory = directory;
		this.codec = codec;
		this.metrics = metrics == null ? AuditMetrics.noop() : metrics;
		this.maxFileBytes = maxFileBytes <= 0 ? 64L * 1024 * 1024 : maxFileBytes;
		this.maxHistory = maxHistory <= 0 ? 10 : maxHistory;
		this.writeQueue = new BoundedRingQueue<>(Math.max(64, queueCapacity));
		this.memoryBufferLimit = memoryBufferLimit <= 0 ? 10_000 : memoryBufferLimit;
		this.checkpoint.set(readCheckpoint());
	}

	/** 打开日志（创建目录、恢复写句柄、启动写线程）。 */
	public synchronized void open() throws IOException {
		if (running.get()) {
			return;
		}
		Files.createDirectories(directory);
		// 启动时需扫描一次现有分段，建立"盘上有效行数"的基线。
		// 计数基线只能在此处全量建立，之后由计数器增量维护，
		// 使采集热路径上的 pendingCount() 不产生 I/O。
		rebuildOnDiskCounters();
		this.currentSegment = nextSegmentNumber();
		openSegment(currentSegment, true);
		running.set(true);
		this.writer = new Thread(this::writeLoop, "audit-log-journal-writer");
		this.writer.setDaemon(true);
		this.writer.start();
		log.info("[audit-log] 本地兜底日志已启动 dir={} segment={} checkpointSeq={} onDiskLines={}",
			directory, currentSegment, checkpoint.get(), onDiskLines.sum());
	}

	/** 重建盘上行数计数器（仅在 {@link #open()} 时调用一次）。 */
	private void rebuildOnDiskCounters() {
		countedSegments.clear();
		long total = 0L;
		for (Path segment : segments()) {
			long lines = countLines(segment);
			countedSegments.put(segment, lines);
			total += lines;
		}
		// 计数器没有归零接口，用 add(-当前值) 复位
		onDiskLines.add(-onDiskLines.sum());
		onDiskLines.add(total);
	}

	@Override
	public boolean append(AuditRecord record) {
		if (record == null || !running.get()) {
			return false;
		}
		if (writeQueue.offer(record)) {
			return true;
		}
		// 第二级降级：写线程的内存缓冲
		synchronized (memoryBuffer) {
			if (memoryBuffer.size() < memoryBufferLimit) {
				memoryBuffer.add(record);
				metrics.increment(AuditMetricNames.JOURNAL_REJECTED);
				rejected.incrementAndGet();
				return true;
			}
		}
		// 第三级：真正丢弃并告警（必须人工介入）
		//
		// 指标归属约定（避免同一个事件被两处计数导致指标翻倍、进而使"不丢数据"无法被证明）：
		//   - AuditJournal 只负责 JOURNAL_REJECTED（"兜底日志拒绝了写入"）；
		//   - DROPPED（"记录真的丢了"）由调用方 AuditPipeline 统一计数，
		//     因为只有它知道被拒之后是丢弃、还是走了别的兜底路径。
		metrics.increment(AuditMetricNames.JOURNAL_REJECTED);
		rejected.incrementAndGet();
		dropped.incrementAndGet();
		log.error("[audit-log] 审计兜底日志已饱和，记录被丢弃 id={} seq={} —— 请立即扩容磁盘或修复存储",
			record.getId(), record.getSeq());
		return false;
	}

	@Override
	public boolean appendDeadLetter(AuditRecord record, String reason) {
		try {
			Path path = directory.resolve(DEAD_LETTER);
			String line = codec.encode(record);
			synchronized (this) {
				Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
			// 此处不递增 audit.store.deadletter：死信计数由调用方（AuditPipeline）
			// 统一负责，两处计数会使指标翻倍，导致"不丢数据"无法被证明。
			// 写入失败通过返回值暴露，由调用方决定如何计数。
			log.error("[audit-log] 审计记录进入死信 reason={} id={} seq={}", reason, record.getId(), record.getSeq());
			return true;
		}
		catch (IOException error) {
			log.error("[audit-log] 写死信失败 id={}", record.getId(), error);
			return false;
		}
	}

	@Override
	public List<AuditRecord> readPending(int maxCount) {
		long from = checkpoint.get();
		List<AuditRecord> pending = new ArrayList<>();
		List<Path> segments = segments();
		for (Path segment : segments) {
			if (pending.size() >= maxCount) {
				break;
			}
			try (Stream<String> lines = Files.lines(segment, StandardCharsets.UTF_8)) {
				java.util.Iterator<String> iterator = lines.iterator();
				while (iterator.hasNext() && pending.size() < maxCount) {
					String line = iterator.next();
					AuditRecord record = codec.decode(line);
					if (record != null && record.getSeq() > from) {
						pending.add(record);
					}
				}
			}
			catch (IOException error) {
				log.warn("[audit-log] 读取兜底日志片段失败 segment={}", segment, error);
			}
		}
		pending.sort(Comparator.comparingLong(AuditRecord::getSeq));
		return pending;
	}

	@Override
	public void checkpoint(long seq) {
		if (seq <= checkpoint.get()) {
			return;
		}
		checkpoint.set(seq);
		try {
			Files.writeString(directory.resolve(CHECKPOINT), Long.toString(seq), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		}
		catch (IOException error) {
			log.warn("[audit-log] 写入重放检查点失败 seq={}", seq, error);
		}
	}

	@Override
	public long pendingCount() {
		// 全部走内存计数，不做任何磁盘 I/O。
		//
		// 本方法是 updateDepthGauge() 的一部分，后者在每次成功入队时都会被调用。
		// 若改用 Files.lines(path).count() 遍历所有 WAL 分段统计盘上待重放行数
		// （分段文件在重放与保留清理完成前一直存在），每次入队都要付出全量 WAL
		// 行扫描的 I/O 代价，与"提交路径只做轻量操作、不做任何 I/O"的约定冲突。
		long onDisk = onDiskLines.sum();
		return writeQueue.size() + memoryBufferSize() + Math.max(0L, onDisk - checkpoint.get());
	}

	/** 统计某片段当前的行数（仅在启动、轮转与清理时调用）。 */
	private long countLines(Path path) {
		try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
			return lines.filter(line -> line != null && !line.isBlank()).count();
		}
		catch (IOException error) {
			return 0L;
		}
	}

	@Override
	public boolean isHealthy() {
		return running.get() && currentFileBytes < maxFileBytes * 4L;
	}

	/** 已落盘记录数。 */
	public long journaledCount() {
		return journaled.get();
	}

	/** 因 WAL 饱和而被拒/丢弃的记录数。 */
	public long rejectedCount() {
		return rejected.get();
	}

	/** 因 WAL 三级降级最终被真正丢弃的记录数。 */
	public long droppedCount() {
		return dropped.get();
	}

	public Path getDirectory() {
		return directory;
	}

	/** 停止写线程并 flush（由框架在停机时调用，保证队列不丢）。 */
	public synchronized void close() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		Thread thread = this.writer;
		if (thread != null) {
			thread.interrupt();
			try {
				thread.join(5_000L);
			}
			catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
		}
		flushRemaining();
		closeQuietly();
		log.info("[audit-log] 本地兜底日志已关闭 dir={} journaled={} rejected={} dropped={}", directory,
			journaled.get(), rejected.get(), dropped.get());
	}

	// ---- 内部实现 ----

	private void writeLoop() {
		while (running.get() || !writeQueue.isEmpty() || memoryBufferSize() > 0) {
			try {
				boolean wrote = drainMemoryBuffer();
				List<AuditRecord> batch = writeQueue.pollBatch(256);
				if (batch.isEmpty() && !wrote) {
					// 空转时短暂休眠：审计日志对写入延迟不敏感，避免空转烧 CPU
					Thread.sleep(50L);
					continue;
				}
				for (AuditRecord record : batch) {
					writeRecord(record);
				}
				synchronized (this) {
					if (writerHandle != null) {
						writerHandle.flush();
					}
				}
			}
			catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				break;
			}
			catch (Throwable error) {
				log.error("[audit-log] 兜底日志写线程异常", error);
			}
		}
	}

	private boolean drainMemoryBuffer() {
		List<AuditRecord> snapshot;
		synchronized (memoryBuffer) {
			if (memoryBuffer.isEmpty()) {
				return false;
			}
			snapshot = new ArrayList<>(memoryBuffer);
			memoryBuffer.clear();
		}
		for (AuditRecord record : snapshot) {
			writeRecord(record);
		}
		return true;
	}

	private void writeRecord(AuditRecord record) {
		try {
			if (writerHandle == null) {
				return;
			}
			String line = codec.encode(record);
			writerHandle.write(line);
			writerHandle.newLine();
			// 按 UTF-8 字节数累计，而不是字符数：String.length() 返回 char 数量，
			// 中文内容下显著小于实际字节数，会使轮转阈值系统性偏小
			// （文件远小于配置上限即触发轮转）。
			currentFileBytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
			onDiskLines.increment();
			journaled.incrementAndGet();
			metrics.increment(AuditMetricNames.JOURNALED);
			if (currentFileBytes >= maxFileBytes) {
				rotate();
			}
		}
		catch (IOException error) {
			log.error("[audit-log] 写兜底日志失败，记录可能丢失 id={}", record.getId(), error);
			metrics.increment(AuditMetricNames.JOURNAL_REJECTED);
		}
	}

	private void rotate() throws IOException {
		closeQuietly();
		currentSegment++;
		retention();
		openSegment(currentSegment, true);
		log.info("[audit-log] 兜底日志已轮转 segment={}", currentSegment);
	}

	private void retention() {
		try {
			List<Path> segments = segments();
			if (segments.size() <= maxHistory) {
				return;
			}
			int removable = segments.size() - maxHistory;
			for (int index = 0; index < removable; index++) {
				Path path = segments.get(index);
				// 安全约束：绝不删除尚未重放完的片段（否则等于丢审计数据）
				if (isFullyReplayed(path)) {
					Files.deleteIfExists(path);
					// 精确扣减：删除前先取出该片段已计入的行数
					Long removed = countedSegments.remove(path);
					if (removed != null) {
						onDiskLines.add(-removed);
					}
					metrics.increment(AuditMetricNames.RETENTION_CLEANED);
					log.info("[audit-log] 保留策略清理已重放片段 {}", path.getFileName());
				}
				else {
					log.warn("[audit-log] 片段 {} 尚未重放完成，保留不删（请检查存储是否可用）", path.getFileName());
					break;
				}
			}
		}
		catch (IOException error) {
			log.warn("[audit-log] 保留策略执行失败", error);
		}
	}

	private boolean isFullyReplayed(Path path) {
		try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
			long maxSeq = lines.map(codec::decode)
				.filter(java.util.Objects::nonNull)
				.mapToLong(AuditRecord::getSeq)
				.max()
				.orElse(Long.MIN_VALUE);
			return maxSeq <= checkpoint.get();
		}
		catch (IOException error) {
			return false;
		}
	}

	private List<Path> segments() {
		if (!Files.isDirectory(directory)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.list(directory)) {
			return stream
				.filter(path -> {
					String name = path.getFileName().toString();
					return name.startsWith(JOURNAL_PREFIX) && name.endsWith(JOURNAL_SUFFIX);
				})
				.sorted()
				.toList();
		}
		catch (IOException error) {
			return List.of();
		}
	}

	private int nextSegmentNumber() {
		List<Path> existing = segments();
		if (existing.isEmpty()) {
			return 1;
		}
		String name = existing.get(existing.size() - 1).getFileName().toString();
		try {
			return Integer.parseInt(name.substring(JOURNAL_PREFIX.length(), name.length() - JOURNAL_SUFFIX.length()))
				+ 1;
		}
		catch (NumberFormatException ignored) {
			return existing.size() + 1;
		}
	}

	private void openSegment(int segment, boolean append) throws IOException {
		Path path = directory.resolve(JOURNAL_PREFIX + String.format("%05d", segment) + JOURNAL_SUFFIX);
		this.writerHandle = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		this.currentFileBytes = Files.exists(path) ? Files.size(path) : 0L;
	}

	private void closeQuietly() {
		try {
			if (writerHandle != null) {
				writerHandle.flush();
				writerHandle.close();
			}
		}
		catch (IOException ignored) {
			// 关闭异常不影响停机流程
		}
		finally {
			writerHandle = null;
		}
	}

	private void flushRemaining() {
		List<AuditRecord> remaining = writeQueue.drainAll();
		synchronized (memoryBuffer) {
			remaining.addAll(memoryBuffer);
			memoryBuffer.clear();
		}
		if (remaining.isEmpty()) {
			return;
		}
		try {
			openSegment(currentSegment, true);
			for (AuditRecord record : remaining) {
				writeRecord(record);
			}
			closeQuietly();
		}
		catch (IOException error) {
			log.error("[audit-log] 停机时兜底日志 flush 失败，{} 条记录可能丢失", remaining.size(), error);
		}
	}

	private long memoryBufferSize() {
		synchronized (memoryBuffer) {
			return memoryBuffer.size();
		}
	}

	private long readCheckpoint() {
		try {
			Path path = directory.resolve(CHECKPOINT);
			if (Files.exists(path)) {
				return Long.parseLong(Files.readString(path, StandardCharsets.UTF_8).trim());
			}
		}
		catch (Exception ignored) {
			// 检查点损坏时从 0 开始重放：重复投递由存储幂等保证，比漏投安全
		}
		return 0L;
	}
}
