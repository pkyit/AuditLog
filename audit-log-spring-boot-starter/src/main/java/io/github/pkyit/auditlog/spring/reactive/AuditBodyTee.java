package io.github.pkyit.auditlog.spring.reactive;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 请求体旁路缓存（tee）：业务读到完整数据，审计同时留存受限副本。
 *
 * <p>HTTP 请求体只能被消费一次，而审计需要在业务消费之后仍能取到内容，
 * 因此只能在上游数据流上做观察式拷贝。旁路拷贝必须同时满足以下约束：
 * <ol>
 *   <li>只观察、不消费：拷贝通过 {@code doOnNext} 完成，业务仍拿到原始 {@code DataBuffer}。
 *       此处不得 {@code subscribe()} / {@code block()}，否则请求体被读空，业务收到空体。</li>
 *   <li>不改变读位置：拷贝必须按绝对位置取内容；读位置一旦被推进，
 *       业务读到的请求体将缺字节。</li>
 *   <li>不在此处释放：{@code DataBufferUtils.release} 只能由数据所有者在消费完成后调用，
 *       旁观者提前释放会让业务拿到已归还缓冲池的 {@code DataBuffer}。</li>
 *   <li>必须有上限：请求体可能极大，达到上限后停止拷贝并标记截断，数据流本身继续透传。</li>
 * </ol>
 */
final class AuditBodyTee {

	private AuditBodyTee() {
	}

	/**
	 * 给上游数据流附加旁路拷贝，返回原样透传的流。
	 *
	 * <p>拷贝只作为副作用发生：元素、顺序与内容均不改变。拷贝自身抛出的异常被吞掉，
	 * 仅将 {@code sink} 标记为截断，业务数据流不受影响。
	 *
	 * @param source   原始请求体数据流
	 * @param maxBytes 缓存上限（字节）；{@code <= 0} 时回退为
	 *                 {@link AuditReactiveSupport#DEFAULT_MAX_BODY_BYTES}
	 * @param sink     累积容器，接收拷贝内容并记录截断状态
	 * @return 与 {@code source} 元素及顺序完全一致的流
	 */
	static Flux<DataBuffer> tee(Flux<DataBuffer> source, int maxBytes, BodySink sink) {
		int limit = maxBytes <= 0 ? AuditReactiveSupport.DEFAULT_MAX_BODY_BYTES : maxBytes;
		return source.doOnNext(buffer -> {
			try {
				if (sink.isTruncated()) {
					return;
				}
				int remaining = limit - sink.size();
				int readable = buffer.readableByteCount();
				if (remaining <= 0) {
					sink.markTruncated();
					return;
				}
				int toCopy = Math.min(readable, remaining);
				if (toCopy < readable) {
					sink.markTruncated();
				}
				// 按绝对位置复制内容：toByteBuffer(start, end) 不推进读位置，
				// 业务随后读取的请求体不受影响。read(byte[]) 会推进读位置，此处不可使用。
				byte[] chunk = new byte[toCopy];
				buffer.toByteBuffer(buffer.readPosition(), buffer.readPosition() + toCopy).get(chunk);
				sink.write(chunk, toCopy);
			}
			catch (Throwable ignored) {
				// 拷贝失败不得影响业务数据流；标记截断以表明审计副本不完整
				sink.markTruncated();
			}
		});
	}

	/**
	 * 旁路拷贝的累积容器。
	 *
	 * <p>按字节累积、最后一次性解码：数据分片边界可能落在多字节字符中间，
	 * 逐片解码会产生乱码。
	 *
	 * <p>写入与读取可能发生在不同线程（数据流线程与收尾线程），
	 * 内容与截断状态通过 {@code synchronized} 和 {@code volatile} 保证可见性。
	 */
	static final class BodySink {

		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
		private volatile boolean truncated;

		synchronized void write(byte[] chunk, int length) {
			bytes.write(chunk, 0, length);
		}

		synchronized int size() {
			return bytes.size();
		}

		void markTruncated() {
			this.truncated = true;
		}

		boolean isTruncated() {
			return truncated;
		}

		/**
		 * 返回已累积内容的文本。
		 *
		 * @param charset 解码字符集；{@code null} 时按 UTF-8 处理
		 * @return 无累积内容时返回 {@code null}；内容被截断时在末尾追加 {@code ...(truncated)}
		 */
		synchronized String text(Charset charset) {
			if (bytes.size() == 0) {
				return null;
			}
			String decoded = bytes.toString(charset == null ? StandardCharsets.UTF_8 : charset);
			return truncated ? decoded + "...(truncated)" : decoded;
		}
	}
}
