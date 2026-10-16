package io.github.pkyit.auditlog.core.chain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 摘要与签名工具。
 *
 * <p>只使用 JDK 自带的 {@code java.security} 实现，不引入第三方加密库：
 * 审计链属于安全边界，依赖面越小，可被供应链投毒的面越小。
 *
 * <p>全部方法无状态，可被多线程并发调用。
 */
public final class Hasher {

	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private Hasher() {
	}

	/**
	 * 计算 SHA-256 摘要。
	 *
	 * <p>输入按 UTF-8 编码，输出为小写十六进制，固定 64 个字符。
	 *
	 * @param value 待摘要内容，不可为 {@code null}
	 * @return 小写十六进制摘要
	 * @throws IllegalStateException 当前 JVM 不支持 SHA-256（标准实现必然支持，仅作兜底）
	 */
	public static String sha256(String value) {
		try {
			return toHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("当前 JVM 不支持 SHA-256", error);
		}
	}

	/**
	 * 计算 HMAC-SHA256 签名。
	 *
	 * <p>密钥与消息均按 UTF-8 编码，输出为小写十六进制。
	 *
	 * @param secret HMAC 密钥，不可为 {@code null}
	 * @param value 待签名内容，不可为 {@code null}
	 * @return 小写十六进制签名
	 * @throws IllegalStateException 密钥非法或当前 JVM 不支持 HmacSHA256
	 */
	public static String hmacSha256(String secret, String value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return toHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception error) {
			throw new IllegalStateException("无法计算 HMAC-SHA256", error);
		}
	}

	/**
	 * 恒定时间比较两个十六进制摘要或签名。
	 *
	 * <p>必须使用恒定时间比较：普通字符串比较在首个不同字符处返回，
	 * 比较耗时随匹配前缀长度变化，会形成可推断签名的侧信道。
	 *
	 * @param left 待比较值，可为 {@code null}
	 * @param right 待比较值，可为 {@code null}
	 * @return 两者内容完全相同且均非 {@code null} 时为 {@code true}，否则为 {@code false}
	 */
	public static boolean constantTimeEquals(String left, String right) {
		if (left == null || right == null) {
			return false;
		}
		return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * 把链头恢复到指定的历史哈希（若该链实现支持）。
	 *
	 * <p>链头是内存态：进程重启后必须从持久化状态恢复，否则新记录的
	 * {@code prevHash} 无法与存储中最后一条衔接，跨重启校验必然报"被篡改"。
	 *
	 * <p>用"由实现自报能力"而不是 {@code instanceof} 判断，
	 * 这样自定义 {@link io.github.pkyit.auditlog.core.api.AuditChain} 实现
	 * 只要提供同名方法即可参与恢复，不必依赖具体类型。
	 *
	 * @param chain 目标链实现；为 {@code null} 时直接返回 {@code false}
	 * @param previousHead 已投递的最后一条记录的 recordHash
	 * @return 是否成功恢复；{@code false} 表示该实现不支持或入参无效（调用方应告警）
	 * @throws IllegalStateException 目标方法存在但调用失败，此时不是"不支持恢复"，
	 *         必须抛出让调用方感知
	 */
	public static boolean restoreIfSupported(
			io.github.pkyit.auditlog.core.api.AuditChain chain, String previousHead) {
		if (chain == null || previousHead == null || previousHead.isBlank()) {
			return false;
		}
		try {
			java.lang.reflect.Method method = chain.getClass().getMethod("restoreHead", String.class);
			Object result = method.invoke(chain, previousHead);
			return Boolean.TRUE.equals(result);
		}
		catch (NoSuchMethodException notSupported) {
			return false;
		}
		catch (ReflectiveOperationException error) {
			throw new IllegalStateException("恢复链头失败：" + chain.getClass().getName(), error);
		}
	}

	/**
	 * 把字节数组转为小写十六进制字符串。
	 *
	 * <p>逐字节按无符号值格式化，因此高位为 1 的字节不会被扩展成 {@code ffffffxx}。
	 *
	 * @param bytes 待转换字节，不可为 {@code null}
	 * @return 长度为 {@code bytes.length * 2} 的小写十六进制字符串
	 */
	public static String toHex(byte[] bytes) {
		char[] chars = new char[bytes.length * 2];
		for (int index = 0; index < bytes.length; index++) {
			int value = bytes[index] & 0xFF;
			chars[index * 2] = HEX[value >>> 4];
			chars[index * 2 + 1] = HEX[value & 0x0F];
		}
		return new String(chars);
	}
}
