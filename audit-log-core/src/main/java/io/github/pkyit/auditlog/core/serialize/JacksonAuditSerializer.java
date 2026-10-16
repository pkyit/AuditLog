package io.github.pkyit.auditlog.core.serialize;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.pkyit.auditlog.core.api.AuditSerializer;
import io.github.pkyit.auditlog.core.api.CaptureOptions;
import io.github.pkyit.auditlog.core.model.Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * 基于 Jackson 的默认序列化实现。
 *
 * <p>契约：
 * <ul>
 *   <li>{@link #serialize} 不抛出任何异常：序列化失败时降级为占位符载荷，
 *       占位符用 {@code getClass().getName()} 而非 {@code toString()} 描述对象，
 *       否则业务 {@code toString()} 自身的异常会击穿降级路径；</li>
 *   <li>嵌套深度通过 {@link StreamWriteConstraints} 限制为 64 层，超过时抛出可控的序列化异常，
 *       而不是 {@code StackOverflowError}；</li>
 *   <li>正文超过 {@link CaptureOptions#maxBodyLength()} 时按字符数截断并置 {@code truncated}，
 *       同时保留截断前的 UTF-8 字节数，使消费方知道数据不完整而不是误以为完整。</li>
 * </ul>
 *
 * <p>本类无可变状态，{@code ObjectMapper} 在配置完成后即可安全并发读写，实例可被多线程共享。
 */
public class JacksonAuditSerializer implements AuditSerializer {

	private static final Logger log = LoggerFactory.getLogger(JacksonAuditSerializer.class);

	private final ObjectMapper objectMapper;
	private final String truncatedMarker;

	/**
	 * 使用 {@link #defaultMapper()} 与默认截断标记 {@code "...(truncated)"}。
	 */
	public JacksonAuditSerializer() {
		this(defaultMapper(), "...(truncated)");
	}

	/**
	 * 使用指定的映射器与默认截断标记。
	 *
	 * @param objectMapper 实际执行序列化的映射器，其配置决定快照的字段与格式
	 */
	public JacksonAuditSerializer(ObjectMapper objectMapper) {
		this(objectMapper, "...(truncated)");
	}

	/**
	 * 使用指定的映射器与截断标记。
	 *
	 * @param objectMapper    实际执行序列化的映射器，其配置决定快照的字段与格式
	 * @param truncatedMarker 截断后追加到正文末尾的标记，供消费方识别不完整数据
	 */
	public JacksonAuditSerializer(ObjectMapper objectMapper, String truncatedMarker) {
		this.objectMapper = objectMapper;
		this.truncatedMarker = truncatedMarker;
	}

	/**
	 * 构造默认 {@link ObjectMapper}。
	 *
	 * <p>不复用 Spring 容器的 {@code ObjectMapper}：审计快照的格式必须跨服务、跨版本稳定，
	 * 不能随宿主应用的序列化配置（命名策略、日期格式、过滤器）漂移。
	 *
	 * @return 新映射器；由调用方持有，本类不做后续配置
	 */
	public static ObjectMapper defaultMapper() {
		// 深度限制必须配置在 JsonFactory 上（ObjectMapper 不提供该入口），
		// 缺少限制时畸形对象图会抛出 StackOverflowError，而不是可捕获的序列化异常。
		JsonFactory factory = JsonFactory.builder()
			.streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(64).build())
			.build();
		ObjectMapper mapper = new ObjectMapper(factory);
		mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
		return mapper;
	}

	/**
	 * 将任意对象序列化为载荷快照。
	 *
	 * <p>{@link CharSequence} 直接按文本处理，不经过对象映射，避免字符串被二次转义。
	 *
	 * <p>本方法不抛异常：{@code null} 返回 {@link Payload#EMPTY}；不可序列化的对象返回
	 * {@code contentType} 为 {@code unserializable} 的占位符载荷，其 {@code originalSize}
	 * 为 {@code -1}（长度未知，不可当作真实长度），{@code truncated} 为 {@code false}。
	 *
	 * @param value   待序列化对象，允许为 {@code null}
	 * @param options 采集选项，决定正文的最大长度
	 * @param label   用途标签，仅用于日志定位
	 * @return 载荷快照；{@code value} 为 {@code null} 时为 {@link Payload#EMPTY}
	 */
	@Override
	public Payload serialize(Object value, CaptureOptions options, String label) {
		if (value == null) {
			return Payload.EMPTY;
		}
		// 纯文本与 JSON 字符串不需要经过对象映射，避免二次转义
		if (value instanceof CharSequence text) {
			return truncate(text.toString(), options, classifyText(text.toString()));
		}
		try {
			String json = objectMapper.writeValueAsString(value);
			return truncate(json, options, "json");
		}
		catch (Throwable error) {
			// 必须捕获 Throwable：业务对象的 getter 可能抛出 Error，降级路径不能因此被击穿
			log.debug("[audit-log] {} 序列化失败，降级为占位符: {}", label, error.toString());
			return new Payload("unserializable",
				"{\"__unserializable__\":\"" + value.getClass().getName() + "\",\"__error__\":\""
					+ escape(error.getClass().getSimpleName()) + "\"}",
				-1L, false);
		}
	}

	// 启发式判定：仅忽略前导空白并检查首尾字符是否配对，
	// 因此带尾随空白的 JSON 文本会被归类为 text
	private String classifyText(String text) {
		String trimmed = text.stripLeading();
		boolean looksJson = (trimmed.startsWith("{") && trimmed.endsWith("}"))
			|| (trimmed.startsWith("[") && trimmed.endsWith("]"));
		return looksJson ? "json" : "text";
	}

	private Payload truncate(String body, CaptureOptions options, String contentType) {
		if (body == null) {
			return Payload.EMPTY;
		}
		// 截断阈值按字符数比较，originalSize 记录 UTF-8 字节数：两者单位不同，
		// 多字节字符场景下截断点的字符数不等于字节数
		long originalSize = body.getBytes(StandardCharsets.UTF_8).length;
		int max = options.maxBodyLength();
		if (body.length() <= max) {
			return new Payload(contentType, body, originalSize, false);
		}
		return new Payload(contentType, body.substring(0, max) + truncatedMarker, originalSize, true);
	}

	// 该载荷是手工拼接的 JSON，值中残留的双引号必须替换掉，否则会破坏 JSON 结构
	private static String escape(String value) {
		return value == null ? "" : value.replace("\"", "'");
	}
}
