package io.github.pkyit.auditlog.spring.method;

import io.github.pkyit.auditlog.core.model.ActionType;
import io.github.pkyit.auditlog.spring.annotation.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AuditMethodRegistry} 的 Servlet 实现：容器刷新完成后全量扫描 HandlerMapping。
 *
 * <p>本类是本模块中少数几个合法引用 Servlet 类型的地方，因此只能被带
 * {@code @ConditionalOnWebApplication(SERVLET)} 条件的配置引用；
 * 否则 WebFlux 环境下会因类不可解析而抛 {@code NoClassDefFoundError}，导致应用启动失败。
 *
 * <p>元数据必须取自 Spring 真实的 HandlerMapping，且以 {@code 类名#方法名(参数签名)} 作稳定 key：
 * <ul>
 *   <li>自行 {@code forName} 加载类会触发静态初始化，可能因可选依赖缺失而拖垮应用启动；</li>
 *   <li>直接持有 {@code Method} 对象会在 ClassLoader 变化后失效；</li>
 *   <li>失配项经 {@link #excludedHandlers()} 显式报告，不得静默跳过。</li>
 * </ul>
 *
 * <p>线程安全：写入的字段均为 {@code volatile}，查询方看到的是某一次完整写入后的快照。
 * {@link #populate} 只允许在容器刷新完成时调用一次；{@link #find} 可被多线程并发调用，
 * 其回退匹配会把已告警的 key 写入并发集合，同一 key 的告警至多输出一次。
 */
public class ServletMethodRegistry implements AuditMethodRegistry {

	private static final Logger log = LoggerFactory.getLogger(ServletMethodRegistry.class);

	private volatile Map<String, AuditMethodMetadata> metadata = Map.of();
	private volatile List<String> excludedHandlers = List.of();
	private volatile int scannedHandlerCount;
	private volatile boolean populated;

	private final java.util.Set<String> warnedKeys = ConcurrentHashMap.newKeySet();

	/**
	 * 创建空注册表，等待 {@link #populate} 填充；未填充前 {@link #isPopulated()} 返回 {@code false}。
	 *
	 * @return 元数据为空的注册表实例
	 */
	public static ServletMethodRegistry empty() {
		return new ServletMethodRegistry();
	}

	/**
	 * 在容器刷新完成后填充注册表。
	 *
	 * <p>重复调用会以最后一次结果整体替换已有内容。
	 *
	 * @param handlerMapping         Spring MVC 的主请求映射，可为 {@code null}（非 Web 场景），
	 *                               为 {@code null} 时不扫描任何 Handler
	 * @param methodCollectorEnabled 是否启用方法级采集；为 {@code false} 时元数据保持为空，
	 *                               但已标注注解的 Handler 仍会被计入 {@link #excludedHandlers()}
	 */
	public void populate(RequestMappingHandlerMapping handlerMapping, boolean methodCollectorEnabled) {
		Map<String, AuditMethodMetadata> result = new LinkedHashMap<>();
		List<String> annotatedButNotCollected = new ArrayList<>();
		int scanned = 0;
		if (handlerMapping != null) {
			for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
				HandlerMethod handlerMethod = entry.getValue();
				scanned++;
				Method method = handlerMethod.getMethod();
				String key = keyOf(handlerMethod);
				AuditLog annotation = resolveAnnotation(handlerMethod);
				if (annotation != null && annotation.ignore()) {
					annotatedButNotCollected.add(key + "（注解标记 ignore=true）");
					continue;
				}
				if (!methodCollectorEnabled) {
					if (annotation != null) {
						annotatedButNotCollected
							.add(key + "（方法级采集已关闭 collector.method.enabled=false）");
					}
					continue;
				}
				if (annotation != null) {
					result.put(key, AuditMethodMetadata.of(key, handlerMethod, annotation));
				}
			}
		}
		this.metadata = Collections.unmodifiableMap(result);
		this.excludedHandlers = List.copyOf(annotatedButNotCollected);
		this.scannedHandlerCount = scanned;
		this.populated = true;
		if (!annotatedButNotCollected.isEmpty()) {
			log.warn("[audit-log] 检测到 {} 个标注了 @AuditLog 但未被采集的 Handler：{}",
				annotatedButNotCollected.size(), annotatedButNotCollected);
		}
	}

	/**
	 * 解析方法上生效的 {@code @AuditLog}：先查方法级，未命中再回退查声明类。
	 *
	 * <p>注解声明了 {@code @Target({METHOD, TYPE})} + {@code @Inherited}，
	 * 但 {@code AnnotatedElementUtils.findMergedAnnotation(Method, ...)} 不会向上查找声明类上的注解，
	 * 因此必须显式回退；否则类级用法会静默失效。
	 *
	 * <p>合并语义：方法级覆盖类级。
	 *
	 * @return 方法级与类级均无注解时返回 {@code null}
	 */
	static AuditLog resolveAnnotation(HandlerMethod handlerMethod) {
		AuditLog onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), AuditLog.class);
		if (onMethod != null) {
			return onMethod;
		}
		Class<?> beanType = handlerMethod.getBeanType();
		if (beanType == null) {
			return null;
		}
		return AnnotatedElementUtils.findMergedAnnotation(beanType, AuditLog.class);
	}

	/**
	 * 精确查找该 Handler 的审计元数据；key 未命中时，在同一个声明类范围内按方法名回退匹配。
	 *
	 * <p>回退匹配命中时会输出一次性告警，用于暴露 Handler 方法签名与注册表不一致的情况。
	 *
	 * @param handlerMethod Spring MVC 已匹配的 Handler 方法
	 * @return 精确匹配与回退匹配均未命中时返回 {@code null}
	 */
	@Override
	public AuditMethodMetadata find(HandlerMethod handlerMethod) {
		AuditMethodMetadata found = metadata.get(keyOf(handlerMethod));
		if (found == null && !warnedKeys.contains(keyOf(handlerMethod))) {
			// 回退匹配必须约束在同一个声明类内：仅按裸方法名跨控制器回退时，
			// 多控制器存在同名方法（getById/list/save 常见）会产生系统性元数据错配，
			// 使审计记录带上其他控制器的操作名，其危害大于缺失语义。
			Class<?> declaringClass = handlerMethod.getMethod().getDeclaringClass();
			Class<?> beanType = handlerMethod.getBeanType();
			AuditMethodMetadata fallback = metadata.values().stream()
				.filter(candidate -> candidate.methodName().equals(handlerMethod.getMethod().getName()))
				.filter(candidate -> sameDeclarationScope(candidate, declaringClass, beanType))
				.findFirst()
				.orElse(null);
			if (fallback == null) {
				return null;
			}
			if (warnedKeys.add(keyOf(handlerMethod))) {
				log.warn("[audit-log] Handler 方法签名与注册表不一致，已在同一声明类内回退匹配：{} -> {}",
					keyOf(handlerMethod), fallback.key());
			}
			return fallback;
		}
		return found;
	}

	private static boolean sameDeclarationScope(AuditMethodMetadata candidate, Class<?> declaringClass,
			Class<?> beanType) {
		String key = candidate.key();
		int hashIndex = key.indexOf('#');
		if (hashIndex <= 0) {
			return false;
		}
		String className = key.substring(0, hashIndex);
		if (declaringClass != null && declaringClass.getName().equals(className)) {
			return true;
		}
		if (beanType != null && beanType.getName().equals(className)) {
			return true;
		}
		return isAncestor(className, declaringClass) || isAncestor(className, beanType);
	}

	private static boolean isAncestor(String className, Class<?> type) {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			if (current.getName().equals(className)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public Map<String, AuditMethodMetadata> all() {
		return metadata;
	}

	@Override
	public List<String> excludedHandlers() {
		return excludedHandlers;
	}

	@Override
	public int scannedHandlerCount() {
		return scannedHandlerCount;
	}

	@Override
	public boolean isPopulated() {
		return populated;
	}

	/**
	 * 稳定 key：类名#方法名(参数类型)。
	 *
	 * <p>类名取方法声明类的全限定名而非 Bean 类型，因此代理与继承场景下的 key 保持一致。
	 *
	 * @param handlerMethod Spring MVC 已匹配的 Handler 方法
	 * @return 不含 {@code null} 的 key 字符串
	 */
	public static String keyOf(HandlerMethod handlerMethod) {
		Method method = handlerMethod.getMethod();
		StringBuilder builder = new StringBuilder(96);
		builder.append(method.getDeclaringClass().getName()).append('#').append(method.getName()).append('(');
		Class<?>[] parameters = method.getParameterTypes();
		for (int index = 0; index < parameters.length; index++) {
			if (index > 0) {
				builder.append(',');
			}
			builder.append(parameters[index].getSimpleName());
		}
		return builder.append(')').toString();
	}

	/**
	 * 便捷构造：生成只含单条元数据、且已标记为填充完成的注册表，无需调用 {@link #populate}。
	 *
	 * <p>仅供测试使用。
	 *
	 * @param key   该条元数据的稳定标识
	 * @param value 该条元数据
	 * @return 已填充的注册表实例
	 */
	public static ServletMethodRegistry single(String key, AuditMethodMetadata value) {
		ServletMethodRegistry registry = new ServletMethodRegistry();
		Map<String, AuditMethodMetadata> map = new LinkedHashMap<>();
		map.put(key, value);
		registry.metadata = Collections.unmodifiableMap(map);
		registry.excludedHandlers = List.of();
		registry.scannedHandlerCount = 1;
		registry.populated = true;
		return registry;
	}

	/**
	 * 注解未指定动作类型时的兜底解析：把配置字符串解析为动作类型。
	 *
	 * <p>空白值与非法值一律回退为 {@link ActionType#UNKNOWN}，不抛异常；大小写不敏感。
	 *
	 * @param configured 配置项给出的动作类型名，允许为 {@code null}
	 * @return 解析结果，无法解析时为 {@link ActionType#UNKNOWN}
	 */
	public static ActionType defaultActionType(String configured) {
		if (configured == null || configured.isBlank()) {
			return ActionType.UNKNOWN;
		}
		try {
			return ActionType.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException ignored) {
			return ActionType.UNKNOWN;
		}
	}
}
