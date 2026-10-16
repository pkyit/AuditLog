package io.github.pkyit.auditlog.core.store;

import io.github.pkyit.auditlog.core.api.AuditCodec;
import io.github.pkyit.auditlog.core.api.AuditMetricNames;
import io.github.pkyit.auditlog.core.api.AuditMetrics;
import io.github.pkyit.auditlog.core.api.AuditStore;
import io.github.pkyit.auditlog.core.model.AuditRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 追加写 JSON Lines 文件存储（生产可用的最小实现）。
 *
 * <p>在不引入任何外部中间件的前提下提供「独立留存 + 追加写 + 可轮转 + 可保留」
 * 的审计存储，从而满足「审计日志与应用日志分离、防止意外删除」的基本要求。
 *
 * <p>能力与边界：
 * <ul>
 *   <li>追加写、每行一条 JSON、单文件轮转、按数量保留；</li>
 *   <li>启动时清理超出保留数的历史文件；</li>
 *   <li>无跨文件事务语义：批量写入中途失败会留下部分记录，
 *       因此重试依赖调用方按 id 幂等去重（框架的 WAL 重放会重复投递）；</li>
 *   <li>适用单实例或共享盘场景；多实例并发写同一目录必须自行分片
 *       （建议按 {@code instanceId} 分子目录），否则片段编号会交错、
 *       保留策略会互相删除对方的文件。</li>
 * </ul>
 */
public class JsonFileAuditStore implements AuditStore {

	private static final Logger log = LoggerFactory.getLogger(JsonFileAuditStore.class);

	private final Path directory;
	private final AuditCodec codec;
	private final AuditMetrics metrics;
	private final long maxFileBytes;
	private final int maxHistory;
	private final String filePrefix;

	private BufferedWriter writer;
	private long currentBytes;
	private int segment = 1;

	/**
	 * 构造存储实例；本构造器不打开文件，首次写入或显式调用 {@link #open()} 时才创建文件。
	 *
	 * <p>非法参数在此处回退为默认值：{@code maxFileBytes} 非正数时取 128 MiB，
	 * {@code maxHistory} 非正数时取 30，{@code filePrefix} 为 null 或空白时取 {@code audit}。
	 */
	public JsonFileAuditStore(Path directory, AuditCodec codec, AuditMetrics metrics,
			long maxFileBytes, int maxHistory, String filePrefix) {
		this.directory = directory;
		this.codec = codec;
		this.metrics = metrics == null ? AuditMetrics.noop() : metrics;
		this.maxFileBytes = maxFileBytes <= 0 ? 128L * 1024 * 1024 : maxFileBytes;
		this.maxHistory = maxHistory <= 0 ? 30 : maxHistory;
		this.filePrefix = (filePrefix == null || filePrefix.isBlank()) ? "audit" : filePrefix;
	}

	/**
	 * 创建目录、解析片段编号、打开写入流并按保留数清理历史文件。
	 *
	 * <p>若末尾文件末行不完整（崩溃残留），则新建片段而不续写该文件：
	 * 追加到损坏行之后会使后续解析全部错位。
	 *
	 * <p>本方法可重复调用。
	 *
	 * @throws IOException 目录创建或文件打开失败
	 */
	public synchronized void open() throws IOException {
		Files.createDirectories(directory);
		List<Path> existing = files();
		if (existing.isEmpty()) {
			this.segment = 1;
		}
		else {
			Path last = existing.get(existing.size() - 1);
			this.segment = parseSegment(last.getFileName().toString());
			boolean lastComplete = isLastLineComplete(last);
			if (!lastComplete) {
				// 崩溃可能损坏最后一行：绝不追加到损坏文件之后，避免后续解析全错
				log.warn("[audit-log] 检测到 {} 末行不完整（疑似崩溃残留），将新建片段", last.getFileName());
				this.segment++;
			}
		}
		openSegment();
		retention();
	}

	/**
	 * 追加写入整批记录，批量结束才 flush；写入流为空时先 {@link #open()}。
	 *
	 * <p>本方法可被多线程并发调用。批次内的记录会先进入缓冲区，因此写入中途失败
	 * 只能保证已 flush 的部分落盘；JVM 退出前应显式调用 {@link #close()}。
	 *
	 * @throws IOException 写入或片段轮转失败；此时本批可能只有部分记录落盘
	 */
	@Override
	public synchronized void save(List<AuditRecord> records) throws IOException {
		if (writer == null) {
			open();
		}
		for (AuditRecord record : records) {
			String line = codec.encode(record);
			writer.write(line);
			writer.newLine();
			// 按 UTF-8 字节数累计（String.length() 是 char 数）：中文内容下
			// char 数明显小于字节数，按 char 计数会让轮转阈值系统性偏小。
			currentBytes += line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
			// 不递增 audit.store.delivered —— 该指标由 AuditPipeline 统一负责（见 ADR-013）
		}
		// 批量结束才 flush：缓冲整批可减少 flush 次数，代价是批次中途失败时只能看见部分记录
		writer.flush();
		if (currentBytes >= maxFileBytes) {
			close();
			segment++;
			openSegment();
			retention();
		}
	}

	/** 存储名称，用于指标标签与存储校验。 */
	@Override
	public String getName() {
		return "json-file";
	}

	/**
	 * 关闭写入流，可重复调用。
	 *
	 * <p>关闭失败只记 WARN 日志，不向上抛出：释放路径不应因 IO 错误中断上层收尾逻辑。
	 */
	public synchronized void close() {
		try {
			if (writer != null) {
				writer.flush();
				writer.close();
			}
		}
		catch (IOException error) {
			log.warn("[audit-log] 关闭审计文件失败", error);
		}
		finally {
			writer = null;
		}
	}

	/** 当前存储目录。 */
	public Path getDirectory() {
		return directory;
	}

	/**
	 * 从已落盘的 JSON Lines 文件回读链尾，用于链状态文件缺失时的兜底恢复。
	 *
	 * <p>记录自带 {@code seq} 与 {@code recordHash}，因此状态文件丢失后链仍可续上，
	 * 恢复能力不依赖单一文件。
	 *
	 * <p>本方法不获取实例锁，仅应在启动阶段、无并发写入时调用。
	 *
	 * @return 链尾状态；无可用记录或读取失败时返回 {@code null}，由调用方按冷启动处理
	 */
	@Override
	public io.github.pkyit.auditlog.core.api.ChainState recoverChainState() {
		List<Path> all = files();
		if (all.isEmpty()) {
			return null;
		}
		Path last = all.get(all.size() - 1);
		try {
			List<String> lines = Files.readAllLines(last, java.nio.charset.StandardCharsets.UTF_8);
			// 从最后一行往前找第一条可解码记录：末行可能是崩溃留下的半行
			for (int index = lines.size() - 1; index >= 0; index--) {
				String line = lines.get(index);
				if (line == null || line.isBlank()) {
					continue;
				}
				AuditRecord record = codec.decode(line);
				if (record != null && record.getRecordHash() != null) {
					return new io.github.pkyit.auditlog.core.api.ChainState(
						record.getRecordHash(), record.getSeq());
				}
			}
			return null;
		}
		catch (IOException error) {
			log.warn("[audit-log] 从存储文件回读链尾失败（将按冷启动处理）：{}", error.getMessage());
			return null;
		}
	}

	/**
	 * 列出当前片段文件，按文件名升序，供验收与运维核对。
	 *
	 * @return 不可变列表；目录不存在或列举失败时返回空列表
	 */
	public List<Path> files() {
		if (!Files.isDirectory(directory)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.list(directory)) {
			return stream
				.filter(path -> path.getFileName().toString().startsWith(filePrefix + "-"))
				.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
				.sorted(Comparator.comparing(path -> path.getFileName().toString()))
				.toList();
		}
		catch (IOException error) {
			return List.of();
		}
	}

	private void openSegment() throws IOException {
		Path path = directory.resolve(filePrefix + "-" + String.format("%05d", segment) + ".jsonl");
		this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		this.currentBytes = Files.exists(path) ? Files.size(path) : 0L;
		log.info("[audit-log] 审计文件存储已打开 {}", path);
	}

	private void retention() {
		try {
			List<Path> all = files();
			if (all.size() <= maxHistory) {
				return;
			}
			int removable = all.size() - maxHistory;
			for (int index = 0; index < removable; index++) {
				Files.deleteIfExists(all.get(index));
				metrics.increment(AuditMetricNames.RETENTION_CLEANED);
				log.info("[audit-log] 保留策略清理历史审计文件 {}", all.get(index).getFileName());
			}
		}
		catch (IOException error) {
			log.warn("[audit-log] 保留策略执行失败", error);
		}
	}

	private int parseSegment(String fileName) {
		try {
			String number = fileName.substring(filePrefix.length() + 1, fileName.length() - ".jsonl".length());
			return Integer.parseInt(number);
		}
		catch (RuntimeException ignored) {
			// 文件名不符合片段命名规则：回退为 1，由 openSegment() 生成新的可用片段
			return 1;
		}
	}

	private boolean isLastLineComplete(Path path) {
		try {
			long size = Files.size(path);
			if (size == 0L) {
				return true;
			}
			byte[] lastByte = new byte[1];
			try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(path.toFile(), "r")) {
				file.seek(size - 1);
				file.readFully(lastByte);
			}
			char value = (char) (lastByte[0] & 0xFF);
			return value == '\n' || value == '\r';
		}
		catch (IOException error) {
			// 读取失败时按不完整处理：宁可新建片段，也不冒续写损坏文件的风险
			return false;
		}
	}
}
