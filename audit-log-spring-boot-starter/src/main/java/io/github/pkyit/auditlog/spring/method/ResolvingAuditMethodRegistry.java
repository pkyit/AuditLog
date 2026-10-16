package io.github.pkyit.auditlog.spring.method;

import io.github.pkyit.auditlog.spring.annotation.AuditLog;
import org.springframework.web.method.HandlerMethod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求期解析注解的注册表实现，供 Reactive 环境使用。
 *
 * <p>该环境必须注册本实现而非 {@link AuditMethodRegistry#empty()}：
 * 语义元数据只能经注册表的 {@code find()} 获取，空注册表恒返回 {@code null}，
 * 会使语义通道静默失效（表现为 {@code operation} 为空、{@code actionType} 为
 * {@link io.github.pkyit.auditlog.core.model.ActionType#UNKNOWN}，
 * 即使 HandlerAdapter 包装与注解解析本身都正确）。
 *
 * <p>WebFlux 的 {@code RequestMappingHandlerMapping} 不公开 handler 注册表，
 * 无法做启动期全量扫描，因此改为按需解析：请求期拿到已匹配的 {@code HandlerMethod} 后解析注解并缓存。
 *
 * <p>缓存以哨兵条目区分「已解析且确认无注解」与「尚未解析」，
 * 避免无注解的接口每次请求都重做注解解析。
 *
 * <p>线程安全：{@link #find} 可被多线程并发调用，缓存为 {@code ConcurrentHashMap}。
 */
public class ResolvingAuditMethodRegistry implements AuditMethodRegistry {

	/** 哨兵条目：表示该方法确实没有 {@code @AuditLog}，或已标注 {@code ignore=true}。 */
	private static final Entry ABSENT = new Entry(null);

	private final Map<String, Entry> cache = new ConcurrentHashMap<>();

	private record Entry(AuditMethodMetadata metadata) {
	}

	/**
	 * 查找该 Handler 的审计元数据，未命中时解析并写入缓存。
	 *
	 * @param handlerMethod Spring MVC 已匹配的 Handler 方法，允许为 {@code null}
	 * @return {@code handlerMethod} 为 {@code null}、无 {@code @AuditLog} 或标注了
	 *         {@code ignore=true} 时返回 {@code null}
	 */
	@Override
	public AuditMethodMetadata find(HandlerMethod handlerMethod) {
		if (handlerMethod == null) {
			return null;
		}
		String key = AuditAnnotationResolver.keyOf(handlerMethod);
		Entry entry = cache.computeIfAbsent(key, ignored -> resolve(handlerMethod));
		return entry.metadata();
	}

	private static Entry resolve(HandlerMethod handlerMethod) {
		AuditLog annotation = AuditAnnotationResolver.resolve(handlerMethod);
		if (annotation == null || annotation.ignore()) {
			return ABSENT;
		}
		return new Entry(AuditMethodMetadata.of(AuditAnnotationResolver.keyOf(handlerMethod),
			handlerMethod, annotation));
	}

	/**
	 * 已解析的元数据快照。
	 *
	 * <p>只包含已经被 {@link #find} 触发过解析的 Handler：按需解析下，从未匹配到请求的
	 * Handler 不在结果中，因此本方法的返回结果随请求逐步增长。
	 */
	@Override
	public Map<String, AuditMethodMetadata> all() {
		Map<String, AuditMethodMetadata> result = new LinkedHashMap<>();
		cache.forEach((key, entry) -> {
			if (entry.metadata() != null) {
				result.put(key, entry.metadata());
			}
		});
		return result;
	}

	@Override
	public boolean isPopulated() {
		// 请求期解析：不依赖启动填充
		return true;
	}
}
