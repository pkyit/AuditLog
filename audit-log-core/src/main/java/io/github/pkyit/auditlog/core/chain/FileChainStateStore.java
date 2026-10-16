package io.github.pkyit.auditlog.core.chain;

import io.github.pkyit.auditlog.core.api.AuditChainStore;
import io.github.pkyit.auditlog.core.api.ChainState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 基于文件的链状态持久化（默认实现）。
 *
 * <p>存储格式为极简的键值行。该文件位于恢复路径上，格式越简单，崩溃时写坏的概率越低，
 * 也越便于人工核对。
 *
 * <p>写入采用"临时文件 + 原子改名"：读取方只能看到改名后的完整文件，
 * 不会读到写了一半的状态。
 *
 * <p>实例不可变且无共享可变状态，可被多线程并发使用。
 */
public class FileChainStateStore implements AuditChainStore {

	private static final Logger log = LoggerFactory.getLogger(FileChainStateStore.class);

	private static final String KEY_HEAD = "head";
	private static final String KEY_SEQ = "lastDeliveredSeq";

	private final Path stateFile;

	/**
	 * 以状态文件路径创建存储。
	 *
	 * @param stateFile 状态文件路径；为 {@code null} 时 {@link #load()} 恒返回 {@code null}，
	 *                  {@link #save(ChainState)} 为空操作，即持久化被关闭
	 */
	public FileChainStateStore(Path stateFile) {
		this.stateFile = stateFile;
	}

	/**
	 * 以目录与文件名指定状态文件位置，等价于 {@code new FileChainStateStore(directory.resolve(fileName))}。
	 *
	 * @param directory 状态文件所在目录，缺失时由 {@link #save(ChainState)} 递归创建
	 * @param fileName 状态文件名
	 */
	public FileChainStateStore(Path directory, String fileName) {
		this(directory.resolve(fileName));
	}

	/**
	 * 返回状态文件路径。
	 *
	 * @return 构造时传入的路径；可能为 {@code null}，此时 {@link #load()} 返回 {@code null}，
	 *         {@link #save(ChainState)} 为空操作
	 */
	public Path path() {
		return stateFile;
	}

	@Override
	public ChainState load() {
		if (stateFile == null || !Files.isRegularFile(stateFile)) {
			return null;
		}
		try {
			String head = null;
			long seq = 0L;
			for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
				int index = line.indexOf('=');
				if (index <= 0) {
					continue;
				}
				String key = line.substring(0, index).trim();
				String value = line.substring(index + 1).trim();
				if (KEY_HEAD.equals(key)) {
					head = value.isEmpty() ? null : value;
				}
				else if (KEY_SEQ.equals(key)) {
					seq = parseLong(value, 0L);
				}
			}
			if (head == null && seq <= 0L) {
				return null;
			}
			return new ChainState(head, seq);
		}
		catch (IOException error) {
			// 状态文件不可读不得中断启动：返回 null 后流水线会退回"从存储读回最大 seq"的兜底路径
			log.warn("[audit-log] 链状态文件读取失败（将尝试从存储恢复）：{}", error.getMessage());
			return null;
		}
	}

	@Override
	public void save(ChainState state) {
		if (stateFile == null) {
			return;
		}
		ChainState effective = state == null ? ChainState.pending() : state;
		String content = KEY_HEAD + '=' + (effective.head() == null ? "" : effective.head()) + '\n'
			+ KEY_SEQ + '=' + effective.lastDeliveredSeq() + '\n';
		try {
			Path parent = stateFile.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path temp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
			Files.writeString(temp, content, StandardCharsets.UTF_8);
			try {
				Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			}
			catch (java.nio.file.AtomicMoveNotSupportedException notSupported) {
				// 文件系统不支持原子改名时退回普通替换，否则状态无法落盘
				Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException error) {
			// 状态丢失会导致重启后的链无法衔接，因此不得吞掉异常
			throw new IllegalStateException(
				"[audit-log] 链状态持久化失败（重启后哈希链将无法衔接）：" + stateFile, error);
		}
	}

	@Override
	public String getName() {
		return "file:" + stateFile;
	}

	private static long parseLong(String value, long fallback) {
		try {
			return Long.parseLong(value);
		}
		catch (NumberFormatException ignored) {
			return fallback;
		}
	}
}
