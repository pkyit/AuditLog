package io.github.pkyit.auditlog.spring.servlet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CachedBodyHttpServletRequest} 的契约测试。
 *
 * <p>核心不变量：业务从 {@code getInputStream()} 读到的字节必须与发送的报文逐字节一致，
 * 与报文长度、缓存上限均无关。缓存上限只约束审计副本的长度；一旦它反过来限制业务读取，
 * 超限报文就会被静默截断，而 HTTP 状态码仍为 200。
 *
 * <p>审计副本语义：累计写入超过上限时缓存内容停在上限处并标记 truncated，字符串视图附截断标记；
 * 业务只消费报文前缀时，副本只包含该前缀而非空；报文为空时不可缓存，字符串视图为 {@code null}。
 */
class CachedBodyHttpServletRequestTest {

	private static final int CACHE_LIMIT = 1024;

	private MockHttpServletRequest requestWith(byte[] body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/load/echo");
		request.setContentType("application/json");
		request.setContent(body);
		return request;
	}

	private String sha256(byte[] data) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
	}

	private byte[] drain(CachedBodyHttpServletRequest wrapper) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] chunk = new byte[512];
		int read;
		try (var input = wrapper.getInputStream()) {
			while ((read = input.read(chunk)) != -1) {
				out.write(chunk, 0, read);
			}
		}
		return out.toByteArray();
	}

	@Test
	@DisplayName("回归：报文远大于缓存上限时，业务仍必须读到【完整】报文")
	void businessReceivesCompleteBodyWhenLargerThanCache() throws Exception {
		byte[] payload = new byte[70000];
		for (int i = 0; i < payload.length; i++) {
			payload[i] = (byte) ('a' + (i % 26));
		}

		CachedBodyHttpServletRequest wrapper =
			CachedBodyHttpServletRequest.tee(requestWith(payload), CACHE_LIMIT);

		byte[] received = drain(wrapper);

		assertEquals(payload.length, received.length,
			"业务读到的字节数必须与发送一致 —— 若等于缓存上限说明报文被截断了");
		assertArrayEquals(payload, received, "业务读到的内容必须与发送的完全一致");
		assertEquals(sha256(payload), sha256(received), "内容摘要必须一致");
	}

	@Test
	@DisplayName("回归：缓存达到上限后继续透传，且审计侧标记 truncated")
	void cacheIsCappedButStreamKeepsFlowing() throws Exception {
		byte[] payload = new byte[70000];
		CachedBodyHttpServletRequest wrapper =
			CachedBodyHttpServletRequest.tee(requestWith(payload), CACHE_LIMIT);

		byte[] received = drain(wrapper);

		assertEquals(70000, received.length, "透传不得截断业务数据");
		assertEquals(CACHE_LIMIT, wrapper.getCachedBody().length,
			"审计缓存应当恰好停在上限");
		assertTrue(wrapper.isTruncated(), "超过上限必须标记为截断（审计侧如实反映）");
		assertTrue(wrapper.isCacheable());
		assertTrue(wrapper.getCachedBodyAsString().endsWith("...(truncated)"),
			"审计载荷字符串应带截断标记");
	}

	@Test
	@DisplayName("报文小于缓存上限：完整缓存、不标记截断")
	void cachesEntireBodyWhenSmallerThanLimit() throws Exception {
		byte[] payload = "{\"data\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
		CachedBodyHttpServletRequest wrapper =
			CachedBodyHttpServletRequest.tee(requestWith(payload), CACHE_LIMIT);

		byte[] received = drain(wrapper);

		assertArrayEquals(payload, received);
		assertArrayEquals(payload, wrapper.getCachedBody(), "小报文应被完整缓存");
		assertFalse(wrapper.isTruncated());
		assertEquals("{\"data\":\"hello\"}", wrapper.getCachedBodyAsString());
	}

	@Test
	@DisplayName("边界：报文长度恰好等于缓存上限时不算截断")
	void exactLimitIsNotTruncated() throws Exception {
		byte[] payload = new byte[CACHE_LIMIT];
		CachedBodyHttpServletRequest wrapper =
			CachedBodyHttpServletRequest.tee(requestWith(payload), CACHE_LIMIT);

		byte[] received = drain(wrapper);

		assertEquals(CACHE_LIMIT, received.length);
		assertEquals(CACHE_LIMIT, wrapper.getCachedBody().length);
		assertFalse(wrapper.isTruncated(), "恰好等于上限不应判为截断");
	}

	@Test
	@DisplayName("分块读取（read(byte[],int,int) 与 read() 混合）不丢字节")
	void handlesMixedReadPatterns() throws Exception {
		byte[] payload = new byte[5000];
		for (int i = 0; i < payload.length; i++) {
			payload[i] = (byte) (i % 251);
		}
		CachedBodyHttpServletRequest wrapper =
			CachedBodyHttpServletRequest.tee(requestWith(payload), CACHE_LIMIT);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (var input = wrapper.getInputStream()) {
			// 单字节读 3 次
			for (int i = 0; i < 3; i++) {
				int b = input.read();
				if (b != -1) {
					out.write(b);
				}
			}
			// 再用块读读完剩余
			byte[] chunk = new byte[777];
			int read;
			while ((read = input.read(chunk, 0, chunk.length)) != -1) {
				out.write(chunk, 0, read);
			}
		}

		assertArrayEquals(payload, out.toByteArray(), "混合读取模式不得丢失任何字节");
	}

	@Test
	@DisplayName("业务只读一部分时，审计仍能拿到已读部分（前缀语义）")
	void cachesWhateverBusinessReadWhenPartiallyConsumed() throws Exception {
		byte[] payload = new byte[70000];
		CachedBodyHttpServletRequest wrapper =
			CachedBodyHttpServletRequest.tee(requestWith(payload), CACHE_LIMIT);

		// 业务只读 300 字节就放弃（例如校验后提前返回）
		try (var input = wrapper.getInputStream()) {
			byte[] head = new byte[300];
			assertEquals(300, input.read(head, 0, 300));
		}

		assertEquals(300, wrapper.getCachedBody().length,
			"审计应拿到业务实际读过的前缀，而不是空");
		assertFalse(wrapper.isTruncated());
	}

	@Test
	@DisplayName("兼容入口 wrap() 仍可用于一次性读取场景")
	void wrapStillWorksForOneShotUse() throws Exception {
		byte[] payload = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
		CachedBodyHttpServletRequest wrapper =
			CachedBodyHttpServletRequest.wrap(requestWith(payload), CACHE_LIMIT);

		assertArrayEquals(payload, wrapper.getCachedBody());
		// 冻结后重复读取返回同一份内容（可重复读）
		assertArrayEquals(payload, drain(wrapper));
		assertArrayEquals(payload, drain(wrapper));
	}

	@Test
	@DisplayName("空报文：不可缓存、无内容、不报错")
	void handlesEmptyBody() throws Exception {
		CachedBodyHttpServletRequest wrapper =
			CachedBodyHttpServletRequest.tee(requestWith(new byte[0]), CACHE_LIMIT);

		assertEquals(0, drain(wrapper).length);
		assertFalse(wrapper.isCacheable());
		assertFalse(wrapper.isTruncated());
		assertEquals(null, wrapper.getCachedBodyAsString());
	}
}
