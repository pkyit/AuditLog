package io.github.pkyit.auditlog.spring.servlet;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 可重复读取请求体的包装器：透传原始流，按需旁路缓存正文。
 *
 * <p>Servlet 输入流只能消费一次，而审计需要在业务读取请求体之后仍能取得正文，
 * 因此字节必须在过滤器入口边读边缓存。
 *
 * <p>包装器不得改变业务读到的数据。业务读多少就透传多少，缓存只按已读字节拷贝，
 * 达到上限后停止拷贝并继续透传：越过上限的正文会让业务读到被截断的报文，
 * 且响应状态码仍为成功，形成静默数据损坏。
 *
 * <p>由此得到的契约：
 * <ul>
 *   <li>业务读到完整报文，与是否包装无关；</li>
 *   <li>审计取得正文的前 {@code maxCacheBytes} 字节，超出部分由 {@link #isTruncated()} 标记；</li>
 *   <li>内存占用不超过 {@code maxCacheBytes}，与请求体实际大小无关。</li>
 * </ul>
 *
 * <p>非线程安全：实例与单个请求绑定，不得跨请求共享。
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

	private final int maxCacheBytes;
	private final ByteArrayOutputStream buffer;
	private boolean truncated;
	private boolean requestBodyConsumed;
	private byte[] snapshot;
	private boolean snapshotTruncated;

	private CachedBodyHttpServletRequest(HttpServletRequest request, int maxCacheBytes) {
		super(request);
		this.maxCacheBytes = Math.max(0, maxCacheBytes);
		this.buffer = new ByteArrayOutputStream(Math.min(Math.max(this.maxCacheBytes, 256), 8192));
	}

	/**
	 * 一次性把请求体读入内存（最多 {@code maxCacheBytes} 字节）。
	 *
	 * <p>本方法消费原始输入流，因此只适用于业务不再读取请求体的场景或单元测试；
	 * 生产路径必须使用 {@link #tee(HttpServletRequest, int)}。
	 *
	 * @param request 被包装的请求
	 * @param maxCacheBytes 缓存上限，负值按 0 处理
	 * @return 已冻结缓存的包装器，可重复读取
	 * @throws IOException 读取原始输入流失败
	 */
	public static CachedBodyHttpServletRequest wrap(HttpServletRequest request, int maxCacheBytes)
			throws IOException {
		CachedBodyHttpServletRequest wrapper = new CachedBodyHttpServletRequest(request, maxCacheBytes);
		try (ServletInputStream input = request.getInputStream()) {
			byte[] chunk = new byte[4096];
			int read;
			while ((read = input.read(chunk)) != -1) {
				wrapper.copy(chunk, 0, read);
			}
		}
		wrapper.requestBodyConsumed = true;
		wrapper.freeze();
		return wrapper;
	}

	/**
	 * 生产入口：透传原始流并旁路缓存，业务读到的数据与未包装时逐字节一致。
	 *
	 * @param request 被包装的请求
	 * @param maxCacheBytes 缓存上限，负值按 0 处理
	 * @return 包装器，未做任何预先读取
	 */
	public static CachedBodyHttpServletRequest tee(HttpServletRequest request, int maxCacheBytes) {
		return new CachedBodyHttpServletRequest(request, maxCacheBytes);
	}

	@Override
	public ServletInputStream getInputStream() throws IOException {
		if (requestBodyConsumed) {
			// 每次必须返回新的流：复用同一个 ByteArrayInputStream 时，
			// 第二次读取的位置已在末尾，只能读到 0 字节，
			// 与"可重复读取"的语义相悖。
			return new ByteArrayServletInputStream(frozenBytes());
		}
		return new TeeServletInputStream();
	}

	@Override
	public BufferedReader getReader() throws IOException {
		if (requestBodyConsumed) {
			return new BufferedReader(
				new InputStreamReader(new ByteArrayInputStream(frozenBytes()), resolveCharset()));
		}
		return new BufferedReader(new InputStreamReader(getInputStream(), resolveCharset()));
	}

	/**
	 * 缓存到的请求体字节。
	 *
	 * @return 请求体前缀；发生截断时为前 {@code maxCacheBytes} 字节，无正文时为空数组
	 */
	public byte[] getCachedBody() {
		return snapshot != null ? snapshot : buffer.toByteArray();
	}

	/**
	 * 请求体是否因超过缓存上限而被截断。
	 *
	 * @return 缓存内容不等于完整请求体时为 {@code true}
	 */
	public boolean isTruncated() {
		return snapshot != null ? snapshotTruncated : truncated;
	}

	/**
	 * 是否缓存到了正文。
	 *
	 * @return 缓存字节数大于 0 时为 {@code true}
	 */
	public boolean isCacheable() {
		return getCachedBody().length > 0;
	}

	/** 缓存上限，即审计最多保留的请求体字节数。 */
	public int maxCacheBytes() {
		return maxCacheBytes;
	}

	/**
	 * 缓存正文的字符串形式，按请求声明的字符集解码。
	 *
	 * @return 无正文时为 {@code null}；被截断时以 {@code ...(truncated)} 结尾
	 */
	public String getCachedBodyAsString() {
		byte[] body = getCachedBody();
		if (body.length == 0) {
			return null;
		}
		String text = new String(body, resolveCharset());
		return isTruncated() ? text + "...(truncated)" : text;
	}

	// ------------------------------------------------------------------
	// 内部实现
	// ------------------------------------------------------------------

	/**
	 * 把已读字节旁路拷入缓存。
	 *
	 * <p>缓存冻结后不再接受任何字节；容量耗尽后只置截断标记并丢弃剩余字节，
	 * 从而保证缓存内容始终是请求体的完整前缀。
	 */
	private void copy(byte[] source, int offset, int length) {
		if (length <= 0 || snapshot != null) {
			return;
		}
		int remaining = maxCacheBytes - buffer.size();
		if (remaining <= 0) {
			truncated = true;
			return;
		}
		if (length > remaining) {
			buffer.write(source, offset, remaining);
			truncated = true;
			return;
		}
		buffer.write(source, offset, length);
	}

	/**
	 * 请求体读尽后冻结缓存：快照一经建立便不可再变，此后的读取只针对快照。
	 *
	 * <p>重复调用是幂等的，保留首次快照。
	 */
	private void freeze() {
		if (snapshot == null) {
			snapshot = buffer.toByteArray();
			snapshotTruncated = truncated;
		}
	}

	/**
	 * 冻结后的字节快照；未冻结时返回当前缓存内容的副本。
	 *
	 * <p>返回值始终是独立数组，调用方修改不会影响缓存。
	 */
	private byte[] frozenBytes() {
		return snapshot != null ? snapshot : buffer.toByteArray();
	}

	/**
	 * 解析请求声明的字符集。
	 *
	 * <p>请求未声明字符集或声明值不可识别时回退为 UTF-8，不抛异常。
	 *
	 * @return 用于解码请求体的字符集，永不为 {@code null}
	 */
	private Charset resolveCharset() {
		String encoding = getCharacterEncoding();
		if (encoding == null || encoding.isBlank()) {
			return StandardCharsets.UTF_8;
		}
		try {
			return Charset.forName(encoding);
		}
		catch (RuntimeException ignored) {
			return StandardCharsets.UTF_8;
		}
	}

	/**
	 * 透传输入流：读取委托给原始流，并把已读字节旁路拷入缓存。
	 *
	 * <p>缓存不参与数据传递，因此不会影响业务收到的内容长度。
	 */
	private final class TeeServletInputStream extends ServletInputStream {

		private final ServletInputStream delegate;

		private TeeServletInputStream() throws IOException {
			this.delegate = CachedBodyHttpServletRequest.super.getInputStream();
		}

		@Override
		public int read() throws IOException {
			int value = delegate.read();
			if (value == -1) {
				freeze();
			}
			else {
				copy(new byte[] { (byte) value }, 0, 1);
			}
			return value;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			int read = delegate.read(bytes, offset, length);
			if (read == -1) {
				freeze();
			}
			else {
				copy(bytes, offset, read);
			}
			return read;
		}

		@Override
		public int available() throws IOException {
			return delegate.available();
		}

		@Override
		public void close() throws IOException {
			// 提前关闭同样视为请求体读取结束，须在委托关闭前冻结缓存
			freeze();
			delegate.close();
		}

		@Override
		public boolean isFinished() {
			return delegate.isFinished();
		}

		@Override
		public boolean isReady() {
			return delegate.isReady();
		}

		@Override
		public void setReadListener(ReadListener readListener) {
			// 异步读取交给原始流处理，避免审计包装器改变容器行为
			delegate.setReadListener(readListener);
		}
	}

	/**
	 * 内存副本输入流，一次性读取已完成缓存的请求体。
	 *
	 * <p>数据已全部在内存中，不涉及容器 I/O，因此不支持异步读取。
	 */
	private static final class ByteArrayServletInputStream extends ServletInputStream {

		private final ByteArrayInputStream input;

		private ByteArrayServletInputStream(byte[] body) {
			this.input = new ByteArrayInputStream(body);
		}

		@Override
		public boolean isFinished() {
			return input.available() == 0;
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setReadListener(ReadListener readListener) {
			// 数据已在内存中，无可注册的异步 I/O 源，未实现即抛异常优于静默忽略
			throw new UnsupportedOperationException("审计采集不支持异步读取监听器");
		}

		@Override
		public int read() {
			return input.read();
		}

		@Override
		public int read(byte[] bytes, int offset, int length) {
			return input.read(bytes, offset, length);
		}
	}
}
